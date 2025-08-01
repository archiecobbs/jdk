/*
 * Copyright (c) 2001, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_GC_G1_G1CONCURRENTMARK_HPP
#define SHARE_GC_G1_G1CONCURRENTMARK_HPP

#include "gc/g1/g1ConcurrentMarkBitMap.hpp"
#include "gc/g1/g1ConcurrentMarkObjArrayProcessor.hpp"
#include "gc/g1/g1HeapRegionSet.hpp"
#include "gc/g1/g1HeapVerifier.hpp"
#include "gc/g1/g1RegionMarkStatsCache.hpp"
#include "gc/shared/gcCause.hpp"
#include "gc/shared/taskqueue.hpp"
#include "gc/shared/taskTerminator.hpp"
#include "gc/shared/verifyOption.hpp"
#include "gc/shared/workerThread.hpp"
#include "gc/shared/workerUtils.hpp"
#include "memory/allocation.hpp"
#include "utilities/compilerWarnings.hpp"
#include "utilities/numberSeq.hpp"

class ConcurrentGCTimer;
class G1CollectedHeap;
class G1ConcurrentMark;
class G1ConcurrentMarkThread;
class G1CMOopClosure;
class G1CMTask;
class G1OldTracer;
class G1RegionToSpaceMapper;
class G1SurvivorRegions;
class ThreadClosure;

// This is a container class for either an oop or a continuation address for
// mark stack entries. Both are pushed onto the mark stack.
class G1TaskQueueEntry {
private:
  void* _holder;

  static const uintptr_t ArraySliceBit = 1;

  G1TaskQueueEntry(oop obj) : _holder(obj) {
    assert(_holder != nullptr, "Not allowed to set null task queue element");
  }
  G1TaskQueueEntry(HeapWord* addr) : _holder((void*)((uintptr_t)addr | ArraySliceBit)) { }
public:

  G1TaskQueueEntry() : _holder(nullptr) { }
  // Trivially copyable, for use in GenericTaskQueue.

  static G1TaskQueueEntry from_slice(HeapWord* what) { return G1TaskQueueEntry(what); }
  static G1TaskQueueEntry from_oop(oop obj) { return G1TaskQueueEntry(obj); }

  oop obj() const {
    assert(!is_array_slice(), "Trying to read array slice " PTR_FORMAT " as oop", p2i(_holder));
    return cast_to_oop(_holder);
  }

  HeapWord* slice() const {
    assert(is_array_slice(), "Trying to read oop " PTR_FORMAT " as array slice", p2i(_holder));
    return (HeapWord*)((uintptr_t)_holder & ~ArraySliceBit);
  }

  bool is_oop() const { return !is_array_slice(); }
  bool is_array_slice() const { return ((uintptr_t)_holder & ArraySliceBit) != 0; }
  bool is_null() const { return _holder == nullptr; }
};

typedef GenericTaskQueue<G1TaskQueueEntry, mtGC> G1CMTaskQueue;
typedef GenericTaskQueueSet<G1CMTaskQueue, mtGC> G1CMTaskQueueSet;

// Closure used by CM during concurrent reference discovery
// and reference processing (during remarking) to determine
// if a particular object is alive. It is primarily used
// to determine if referents of discovered reference objects
// are alive. An instance is also embedded into the
// reference processor as the _is_alive_non_header field
class G1CMIsAliveClosure : public BoolObjectClosure {
  G1ConcurrentMark* _cm;

public:
  G1CMIsAliveClosure();
  G1CMIsAliveClosure(G1ConcurrentMark* cm);
  void initialize(G1ConcurrentMark* cm);

  bool do_object_b(oop obj);
};

class G1CMSubjectToDiscoveryClosure : public BoolObjectClosure {
  G1CollectedHeap* _g1h;
public:
  G1CMSubjectToDiscoveryClosure(G1CollectedHeap* g1h) : _g1h(g1h) { }
  bool do_object_b(oop obj);
};

// Represents the overflow mark stack used by concurrent marking.
//
// Stores oops in a huge buffer in virtual memory that is always fully committed.
// Resizing may only happen during a STW pause when the stack is empty.
//
// Memory is allocated on a "chunk" basis, i.e. a set of oops. For this, the mark
// stack memory is split into evenly sized chunks of oops. Users can only
// add or remove entries on that basis.
// Chunks are filled in increasing address order. Not completely filled chunks
// have a null element as a terminating element.
//
// Every chunk has a header containing a single pointer element used for memory
// management. This wastes some space, but is negligible (< .1% with current sizing).
//
// Memory management is done using a mix of tracking a high water-mark indicating
// that all chunks at a lower address are valid chunks, and a singly linked free
// list connecting all empty chunks.
class G1CMMarkStack {
public:
  // Number of TaskQueueEntries that can fit in a single chunk.
  static const size_t EntriesPerChunk = 1024 - 1 /* One reference for the next pointer */;
private:
  struct TaskQueueEntryChunk {
    TaskQueueEntryChunk* next;
    G1TaskQueueEntry data[EntriesPerChunk];
  };

  class ChunkAllocator {
    // The chunk allocator relies on a growable array data structure that allows resizing without the
    // need to copy existing items. The basic approach involves organizing the array into chunks,
    // essentially creating an "array of arrays"; referred to as buckets in this implementation. To
    // facilitate efficient indexing, the size of the first bucket is set to a power of 2. This choice
    // allows for quick conversion of an array index into a bucket index and the corresponding offset
    // within the bucket. Additionally, each new bucket added to the growable array doubles the capacity of
    // the growable array.
    //
    // Illustration of the growable array data structure.
    //
    //        +----+        +----+----+
    //        |    |------->|    |    |
    //        |    |        +----+----+
    //        +----+        +----+----+
    //        |    |------->|    |    |
    //        |    |        +----+----+
    //        +----+        +-----+-----+-----+-----+
    //        |    |------->|     |     |     |     |
    //        |    |        +-----+-----+-----+-----+
    //        +----+        +-----+-----+-----+-----+-----+-----+-----+----+
    //        |    |------->|     |     |     |     |     |     |     |    |
    //        |    |        +-----+-----+-----+-----+-----+-----+-----+----+
    //        +----+
    //
    size_t _min_capacity;
    size_t _max_capacity;
    size_t _capacity;
    size_t _num_buckets;
    bool _should_grow;
    TaskQueueEntryChunk* volatile* _buckets;
    char _pad0[DEFAULT_PADDING_SIZE];
    volatile size_t _size;
    char _pad4[DEFAULT_PADDING_SIZE - sizeof(size_t)];

    size_t bucket_size(size_t bucket) {
      return (bucket == 0) ?
              _min_capacity :
              _min_capacity * ( 1ULL << (bucket - 1));
    }

    static unsigned int find_highest_bit(uintptr_t mask) {
      return count_leading_zeros(mask) ^ (BitsPerWord - 1U);
    }

    size_t get_bucket(size_t array_idx) {
      if (array_idx < _min_capacity) {
        return 0;
      }

      return find_highest_bit(array_idx) - find_highest_bit(_min_capacity) + 1;
    }

    size_t get_bucket_index(size_t array_idx) {
      if (array_idx < _min_capacity) {
        return array_idx;
      }
      return array_idx - (1ULL << find_highest_bit(array_idx));
    }

    bool reserve(size_t new_capacity);

  public:
    ChunkAllocator();

    ~ChunkAllocator();

    bool initialize(size_t initial_capacity, size_t max_capacity);

    void reset() {
      _size = 0;
      _should_grow = false;
    }

    // During G1CMConcurrentMarkingTask or finalize_marking phases, we prefer to restart the marking when
    // the G1CMMarkStack overflows. Attempts to expand the G1CMMarkStack should be followed with a restart
    // of the marking. On failure to allocate a new chuck, the caller just returns and forces a restart.
    // This approach offers better memory utilization for the G1CMMarkStack, as each iteration of the
    // marking potentially involves traversing fewer unmarked nodes in the graph.

    // However, during the reference processing phase, instead of restarting the marking process, the
    // G1CMMarkStack is expanded upon failure to allocate a new chunk. The decision between these two
    // modes of expansion is determined by the _should_grow parameter.
    void set_should_grow() {
      _should_grow = true;
    }

    size_t capacity() const { return _capacity; }

    // Expand the mark stack doubling its size.
    bool try_expand();
    bool try_expand_to(size_t desired_capacity);

    TaskQueueEntryChunk* allocate_new_chunk();
  };

  ChunkAllocator _chunk_allocator;

  char _pad0[DEFAULT_PADDING_SIZE];
  TaskQueueEntryChunk* volatile _free_list;  // Linked list of free chunks that can be allocated by users.
  char _pad1[DEFAULT_PADDING_SIZE - sizeof(TaskQueueEntryChunk*)];
  TaskQueueEntryChunk* volatile _chunk_list; // List of chunks currently containing data.
  volatile size_t _chunks_in_chunk_list;
  char _pad2[DEFAULT_PADDING_SIZE - sizeof(TaskQueueEntryChunk*) - sizeof(size_t)];

  // Atomically add the given chunk to the list.
  void add_chunk_to_list(TaskQueueEntryChunk* volatile* list, TaskQueueEntryChunk* elem);
  // Atomically remove and return a chunk from the given list. Returns null if the
  // list is empty.
  TaskQueueEntryChunk* remove_chunk_from_list(TaskQueueEntryChunk* volatile* list);

  void add_chunk_to_chunk_list(TaskQueueEntryChunk* elem);
  void add_chunk_to_free_list(TaskQueueEntryChunk* elem);

  TaskQueueEntryChunk* remove_chunk_from_chunk_list();
  TaskQueueEntryChunk* remove_chunk_from_free_list();

 public:
  G1CMMarkStack();
  ~G1CMMarkStack() = default;

  // Alignment and minimum capacity of this mark stack in number of oops.
  static size_t capacity_alignment();

  // Allocate and initialize the mark stack.
  bool initialize();

  // Pushes the given buffer containing at most EntriesPerChunk elements on the mark
  // stack. If less than EntriesPerChunk elements are to be pushed, the array must
  // be terminated with a null.
  // Returns whether the buffer contents were successfully pushed to the global mark
  // stack.
  bool par_push_chunk(G1TaskQueueEntry* buffer);

  // Pops a chunk from this mark stack, copying them into the given buffer. This
  // chunk may contain up to EntriesPerChunk elements. If there are less, the last
  // element in the array is a null pointer.
  bool par_pop_chunk(G1TaskQueueEntry* buffer);

  // Return whether the chunk list is empty. Racy due to unsynchronized access to
  // _chunk_list.
  bool is_empty() const { return _chunk_list == nullptr; }

  size_t capacity() const  { return _chunk_allocator.capacity(); }

  void set_should_grow() {
    _chunk_allocator.set_should_grow();
  }

  // Expand the stack, typically in response to an overflow condition
  void expand();

  // Return the approximate number of oops on this mark stack. Racy due to
  // unsynchronized access to _chunks_in_chunk_list.
  size_t size() const { return _chunks_in_chunk_list * EntriesPerChunk; }

  void set_empty();

  // Apply Fn to every oop on the mark stack. The mark stack must not
  // be modified while iterating.
  template<typename Fn> void iterate(Fn fn) const PRODUCT_RETURN;
};

// Root MemRegions are memory areas that contain objects which references are
// roots wrt to the marking. They must be scanned before marking to maintain the
// SATB invariant.
// Typically they contain the areas from TAMS to top of the regions.
// We could scan and mark through these objects during the concurrent start pause,
// but for pause time reasons we move this work to the concurrent phase.
// We need to complete this procedure before we can evacuate a particular region
// because evacuation might determine that some of these "root objects" are dead,
// potentially dropping some required references.
// Root MemRegions comprise of the contents of survivor regions at the end
// of the GC, and any objects copied into the old gen during GC.
class G1CMRootMemRegions {
  // The set of root MemRegions.
  MemRegion* _root_regions;
  size_t const _max_regions;

  volatile size_t _num_root_regions; // Actual number of root regions.

  volatile size_t _claimed_root_regions; // Number of root regions currently claimed.

  volatile bool _scan_in_progress;
  volatile bool _should_abort;

  void notify_scan_done();

public:
  G1CMRootMemRegions(uint const max_regions);
  ~G1CMRootMemRegions();

  // Reset the data structure to allow addition of new root regions.
  void reset();

  void add(HeapWord* start, HeapWord* end);

  // Reset the claiming / scanning of the root regions.
  void prepare_for_scan();

  // Forces get_next() to return null so that the iteration aborts early.
  void abort() { _should_abort = true; }

  // Return true if the CM thread are actively scanning root regions,
  // false otherwise.
  bool scan_in_progress() { return _scan_in_progress; }

  // Claim the next root MemRegion to scan atomically, or return null if
  // all have been claimed.
  const MemRegion* claim_next();

  // The number of root regions to scan.
  uint num_root_regions() const;

  // Is the given memregion contained in the root regions; the MemRegion must
  // match exactly.
  bool contains(const MemRegion mr) const;

  void cancel_scan();

  // Flag that we're done with root region scanning and notify anyone
  // who's waiting on it. If aborted is false, assume that all regions
  // have been claimed.
  void scan_finished();

  // If CM threads are still scanning root regions, wait until they
  // are done. Return true if we had to wait, false otherwise.
  bool wait_until_scan_finished();
};

// This class manages data structures and methods for doing liveness analysis in
// G1's concurrent cycle.
class G1ConcurrentMark : public CHeapObj<mtGC> {
  friend class G1CMBitMapClosure;
  friend class G1CMConcurrentMarkingTask;
  friend class G1CMDrainMarkingStackClosure;
  friend class G1CMKeepAliveAndDrainClosure;
  friend class G1CMRefProcProxyTask;
  friend class G1CMRemarkTask;
  friend class G1CMRootRegionScanTask;
  friend class G1CMTask;
  friend class G1ConcurrentMarkThread;

  G1ConcurrentMarkThread* _cm_thread;     // The thread doing the work
  G1CollectedHeap*        _g1h;           // The heap

  // Concurrent marking support structures
  G1CMBitMap              _mark_bitmap;

  // Heap bounds
  MemRegion const         _heap;

  // Root region tracking and claiming
  G1CMRootMemRegions      _root_regions;

  // For grey objects
  G1CMMarkStack           _global_mark_stack; // Grey objects behind global finger
  HeapWord* volatile      _finger;            // The global finger, region aligned,
                                              // always pointing to the end of the
                                              // last claimed region

  uint                    _worker_id_offset;
  uint                    _max_num_tasks;    // Maximum number of marking tasks
  uint                    _num_active_tasks; // Number of tasks currently active
  G1CMTask**              _tasks;            // Task queue array (max_worker_id length)

  G1CMTaskQueueSet*       _task_queues; // Task queue set
  TaskTerminator          _terminator;  // For termination

  // Two sync barriers that are used to synchronize tasks when an
  // overflow occurs. The algorithm is the following. All tasks enter
  // the first one to ensure that they have all stopped manipulating
  // the global data structures. After they exit it, they re-initialize
  // their data structures and task 0 re-initializes the global data
  // structures. Then, they enter the second sync barrier. This
  // ensure, that no task starts doing work before all data
  // structures (local and global) have been re-initialized. When they
  // exit it, they are free to start working again.
  WorkerThreadsBarrierSync     _first_overflow_barrier_sync;
  WorkerThreadsBarrierSync     _second_overflow_barrier_sync;

  // Number of completed mark cycles.
  volatile uint           _completed_mark_cycles;

  // This is set by any task, when an overflow on the global data
  // structures is detected
  volatile bool           _has_overflown;
  // True: marking is concurrent, false: we're in remark
  volatile bool           _concurrent;
  // Set at the end of a Full GC so that marking aborts
  volatile bool           _has_aborted;

  // Used when remark aborts due to an overflow to indicate that
  // another concurrent marking phase should start
  volatile bool           _restart_for_overflow;

  ConcurrentGCTimer*      _gc_timer_cm;

  G1OldTracer*            _gc_tracer_cm;

  // Timing statistics. All of them are in ms
  NumberSeq _remark_times;
  NumberSeq _remark_mark_times;
  NumberSeq _remark_weak_ref_times;
  NumberSeq _cleanup_times;

  WorkerThreads* _concurrent_workers;
  uint      _num_concurrent_workers; // The number of marking worker threads we're using
  uint      _max_concurrent_workers; // Maximum number of marking worker threads

  enum class VerifyLocation {
    RemarkBefore,
    RemarkAfter,
    RemarkOverflow,
    CleanupBefore,
    CleanupAfter
  };
  static const char* verify_location_string(VerifyLocation location);
  void verify_during_pause(G1HeapVerifier::G1VerifyType type,
                           VerifyLocation location);

  void finalize_marking();

  void weak_refs_work();

  // After reclaiming empty regions, update heap sizes.
  void compute_new_sizes();

  // Resets all the marking data structures. Called when we have to restart
  // marking or when marking completes (via set_non_marking_state below).
  void reset_marking_for_restart();

  // We do this after we're done with marking so that the marking data
  // structures are initialized to a sensible and predictable state.
  void reset_at_marking_complete();

  // Called to indicate how many threads are currently active.
  void set_concurrency(uint active_tasks);

  // Should be called to indicate which phase we're in (concurrent
  // mark or remark) and how many threads are currently active.
  void set_concurrency_and_phase(uint active_tasks, bool concurrent);

  // Prints all gathered CM-related statistics
  void print_stats();

  HeapWord*           finger()       { return _finger;   }
  bool                concurrent()   { return _concurrent; }
  uint                active_tasks() { return _num_active_tasks; }
  TaskTerminator*     terminator()   { return &_terminator; }

  // Claims the next available region to be scanned by a marking
  // task/thread. It might return null if the next region is empty or
  // we have run out of regions. In the latter case, out_of_regions()
  // determines whether we've really run out of regions or the task
  // should call claim_region() again. This might seem a bit
  // awkward. Originally, the code was written so that claim_region()
  // either successfully returned with a non-empty region or there
  // were no more regions to be claimed. The problem with this was
  // that, in certain circumstances, it iterated over large chunks of
  // the heap finding only empty regions and, while it was working, it
  // was preventing the calling task to call its regular clock
  // method. So, this way, each task will spend very little time in
  // claim_region() and is allowed to call the regular clock method
  // frequently.
  G1HeapRegion* claim_region(uint worker_id);

  // Determines whether we've run out of regions to scan. Note that
  // the finger can point past the heap end in case the heap was expanded
  // to satisfy an allocation without doing a GC. This is fine, because all
  // objects in those regions will be considered live anyway because of
  // SATB guarantees (i.e. their TAMS will be equal to bottom).
  bool out_of_regions() { return _finger >= _heap.end(); }

  // Returns the task with the given id
  G1CMTask* task(uint id) {
    // During concurrent start we use the parallel gc threads to do some work, so
    // we can only compare against _max_num_tasks.
    assert(id < _max_num_tasks, "Task id %u not within bounds up to %u", id, _max_num_tasks);
    return _tasks[id];
  }

  // Access / manipulation of the overflow flag which is set to
  // indicate that the global stack has overflown
  bool has_overflown()           { return _has_overflown; }
  void set_has_overflown()       { _has_overflown = true; }
  void clear_has_overflown()     { _has_overflown = false; }
  bool restart_for_overflow()    { return _restart_for_overflow; }

  // Methods to enter the two overflow sync barriers
  void enter_first_sync_barrier(uint worker_id);
  void enter_second_sync_barrier(uint worker_id);

  // Clear the next marking bitmap in parallel using the given WorkerThreads. If may_yield is
  // true, periodically insert checks to see if this method should exit prematurely.
  void clear_bitmap(WorkerThreads* workers, bool may_yield);

  // Region statistics gathered during marking.
  G1RegionMarkStats* _region_mark_stats;
  // Top pointer for each region at the start of marking. Must be valid for all committed
  // regions.
  HeapWord* volatile* _top_at_mark_starts;
  // Top pointer for each region at the start of the rebuild remembered set process
  // for regions which remembered sets need to be rebuilt. A null for a given region
  // means that this region does not be scanned during the rebuilding remembered
  // set phase at all.
  HeapWord* volatile* _top_at_rebuild_starts;
  // True when Remark pause selected regions for rebuilding.
  bool _needs_remembered_set_rebuild;
public:
  // To be called when an object is marked the first time, e.g. after a successful
  // mark_in_bitmap call. Updates various statistics data.
  void add_to_liveness(uint worker_id, oop const obj, size_t size);
  // Did the last marking find a live object between bottom and TAMS?
  bool contains_live_object(uint region) const { return _region_mark_stats[region]._live_words != 0; }
  // Live bytes in the given region as determined by concurrent marking, i.e. the amount of
  // live bytes between bottom and TAMS.
  size_t live_bytes(uint region) const { return _region_mark_stats[region]._live_words * HeapWordSize; }
  // Set live bytes for concurrent marking.
  void set_live_bytes(uint region, size_t live_bytes) { _region_mark_stats[region]._live_words = live_bytes / HeapWordSize; }
  // Approximate number of incoming references found during marking.
  size_t incoming_refs(uint region) const { return _region_mark_stats[region]._incoming_refs; }

  // Update the TAMS for the given region to the current top.
  inline void update_top_at_mark_start(G1HeapRegion* r);
  // Reset the TAMS for the given region to bottom of that region.
  inline void reset_top_at_mark_start(G1HeapRegion* r);

  inline HeapWord* top_at_mark_start(const G1HeapRegion* r) const;
  inline HeapWord* top_at_mark_start(uint region) const;
  // Returns whether the given object been allocated since marking start (i.e. >= TAMS in that region).
  inline bool obj_allocated_since_mark_start(oop obj) const;

  // Sets the internal top_at_region_start for the given region to current top of the region.
  inline void update_top_at_rebuild_start(G1HeapRegion* r);
  // TARS for the given region during remembered set rebuilding.
  inline HeapWord* top_at_rebuild_start(G1HeapRegion* r) const;

  // Clear statistics gathered during the concurrent cycle for the given region after
  // it has been reclaimed.
  void clear_statistics(G1HeapRegion* r);
  // Notification for eagerly reclaimed regions to clean up.
  void humongous_object_eagerly_reclaimed(G1HeapRegion* r);
  // Manipulation of the global mark stack.
  // The push and pop operations are used by tasks for transfers
  // between task-local queues and the global mark stack.
  bool mark_stack_push(G1TaskQueueEntry* arr) {
    if (!_global_mark_stack.par_push_chunk(arr)) {
      set_has_overflown();
      return false;
    }
    return true;
  }
  bool mark_stack_pop(G1TaskQueueEntry* arr) {
    return _global_mark_stack.par_pop_chunk(arr);
  }
  size_t mark_stack_size() const                { return _global_mark_stack.size(); }
  size_t partial_mark_stack_size_target() const { return _global_mark_stack.capacity() / 3; }
  bool mark_stack_empty() const                 { return _global_mark_stack.is_empty(); }

  void concurrent_cycle_start();
  // Abandon current marking iteration due to a Full GC.
  bool concurrent_cycle_abort();
  void concurrent_cycle_end(bool mark_cycle_completed);

  // Notifies marking threads to abort. This is a best-effort notification. Does not
  // guarantee or update any state after the call. Root region scan must not be
  // running.
  void abort_marking_threads();

  // Total cpu time spent in mark worker threads in seconds.
  double worker_threads_cpu_time_s();

  // Attempts to steal an object from the task queues of other tasks
  bool try_stealing(uint worker_id, G1TaskQueueEntry& task_entry);

  G1ConcurrentMark(G1CollectedHeap* g1h,
                   G1RegionToSpaceMapper* bitmap_storage);
  ~G1ConcurrentMark();

  G1ConcurrentMarkThread* cm_thread() { return _cm_thread; }

  G1CMBitMap* mark_bitmap() const { return (G1CMBitMap*)&_mark_bitmap; }

  // Calculates the number of concurrent GC threads to be used in the marking phase.
  uint calc_active_marking_workers();

  // Resets the global marking data structures, as well as the
  // task local ones; should be called during concurrent start.
  void reset();

  // Moves all per-task cached data into global state.
  void flush_all_task_caches();
  // Prepare internal data structures for the next mark cycle. This includes clearing
  // the next mark bitmap and some internal data structures. This method is intended
  // to be called concurrently to the mutator. It will yield to safepoint requests.
  void cleanup_for_next_mark();

  // Clear the next marking bitmap during safepoint.
  void clear_bitmap(WorkerThreads* workers);

  // These two methods do the work that needs to be done at the start and end of the
  // concurrent start pause.
  void pre_concurrent_start(GCCause::Cause cause);
  void post_concurrent_mark_start();
  void post_concurrent_undo_start();

  // Scan all the root regions and mark everything reachable from
  // them.
  void scan_root_regions();
  bool wait_until_root_region_scan_finished();
  void add_root_region(G1HeapRegion* r);
  bool is_root_region(G1HeapRegion* r);
  void root_region_scan_abort_and_wait();

private:
  G1CMRootMemRegions* root_regions() { return &_root_regions; }

  // Scan a single root MemRegion to mark everything reachable from it.
  void scan_root_region(const MemRegion* region, uint worker_id);

public:

  // Do concurrent phase of marking, to a tentative transitive closure.
  void mark_from_roots();

  // Do concurrent preclean work.
  void preclean();

  void remark();

  void cleanup();

  // Mark in the marking bitmap. Used during evacuation failure to
  // remember what objects need handling. Not for use during marking.
  inline void raw_mark_in_bitmap(oop obj);

  // Clears marks for all objects in the given region in the marking
  // bitmap. This should only be used to clean the bitmap during a
  // safepoint.
  void clear_bitmap_for_region(G1HeapRegion* hr);

  // Verify that there are no collection set oops on the stacks (taskqueues /
  // global mark stack) and fingers (global / per-task).
  // If marking is not in progress, it's a no-op.
  void verify_no_collection_set_oops() PRODUCT_RETURN;

  inline bool do_yield_check();

  uint completed_mark_cycles() const;

  bool has_aborted()      { return _has_aborted; }

  void print_summary_info();

  void threads_do(ThreadClosure* tc) const;

  void print_on(outputStream* st) const;

  // Mark the given object on the marking bitmap if it is below TAMS.
  inline bool mark_in_bitmap(uint worker_id, oop const obj);

  inline bool is_marked_in_bitmap(oop p) const;

  ConcurrentGCTimer* gc_timer_cm() const { return _gc_timer_cm; }

  G1OldTracer* gc_tracer_cm() const { return _gc_tracer_cm; }

private:
  // Rebuilds the remembered sets for chosen regions in parallel and concurrently
  // to the application. Also scrubs dead objects to ensure region is parsable.
  void rebuild_and_scrub();

  uint needs_remembered_set_rebuild() const { return _needs_remembered_set_rebuild; }
};

// A class representing a marking task.
class G1CMTask : public TerminatorTerminator {
private:
  enum PrivateConstants {
    // The regular clock call is called once the scanned words reaches
    // this limit
    words_scanned_period          = 12*1024,
    // The regular clock call is called once the number of visited
    // references reaches this limit
    refs_reached_period           = 1024,
  };

  G1CMObjArrayProcessor       _objArray_processor;

  uint                        _worker_id;
  G1CollectedHeap*            _g1h;
  G1ConcurrentMark*           _cm;
  G1CMBitMap*                 _mark_bitmap;
  // the task queue of this task
  G1CMTaskQueue*              _task_queue;

  G1RegionMarkStatsCache      _mark_stats_cache;
  // Number of calls to this task
  uint                        _calls;

  // When the virtual timer reaches this time, the marking step should exit
  double                      _time_target_ms;
  // Start cpu time of the current marking step
  jlong                       _start_cpu_time_ns;

  // Oop closure used for iterations over oops
  G1CMOopClosure*             _cm_oop_closure;

  // Region this task is scanning, null if we're not scanning any
  G1HeapRegion*               _curr_region;
  // Local finger of this task, null if we're not scanning a region
  HeapWord*                   _finger;
  // Limit of the region this task is scanning, null if we're not scanning one
  HeapWord*                   _region_limit;

  // Number of words this task has scanned
  size_t                      _words_scanned;
  // When _words_scanned reaches this limit, the regular clock is
  // called. Notice that this might be decreased under certain
  // circumstances (i.e. when we believe that we did an expensive
  // operation).
  size_t                      _words_scanned_limit;
  // Initial value of _words_scanned_limit (i.e. what it was
  // before it was decreased).
  size_t                      _real_words_scanned_limit;

  // Number of references this task has visited
  size_t                      _refs_reached;
  // When _refs_reached reaches this limit, the regular clock is
  // called. Notice this this might be decreased under certain
  // circumstances (i.e. when we believe that we did an expensive
  // operation).
  size_t                      _refs_reached_limit;
  // Initial value of _refs_reached_limit (i.e. what it was before
  // it was decreased).
  size_t                      _real_refs_reached_limit;

  // If true, then the task has aborted for some reason
  bool                        _has_aborted;
  // Set when the task aborts because it has met its time quota
  bool                        _has_timed_out;
  // True when we're draining SATB buffers; this avoids the task
  // aborting due to SATB buffers being available (as we're already
  // dealing with them)
  bool                        _draining_satb_buffers;

  // Number sequence of past step times
  NumberSeq                   _step_times_ms;
  // Elapsed time of this task
  double                      _elapsed_time_ms;
  // Termination time of this task
  double                      _termination_time_ms;

  TruncatedSeq                _marking_step_diff_ms;

  // Updates the local fields after this task has claimed
  // a new region to scan
  void setup_for_region(G1HeapRegion* hr);
  // Makes the limit of the region up-to-date
  void update_region_limit();

  // Handles the processing of the current region.
  void process_current_region(G1CMBitMapClosure& bitmap_closure);

  // Claims a new region if available.
  void claim_new_region();

  // Attempts to steal work from other tasks.
  void attempt_stealing();

  // Handles the termination protocol.
  void attempt_termination(bool is_serial);

  // Handles the has_aborted scenario.
  void handle_abort(bool is_serial, double elapsed_time_ms);

  // Called when either the words scanned or the refs visited limit
  // has been reached
  void reached_limit();
  // Recalculates the words scanned and refs visited limits
  void recalculate_limits();
  // Decreases the words scanned and refs visited limits when we reach
  // an expensive operation
  void decrease_limits();
  // Checks whether the words scanned or refs visited reached their
  // respective limit and calls reached_limit() if they have
  void check_limits() {
    if (_words_scanned >= _words_scanned_limit ||
        _refs_reached >= _refs_reached_limit) {
      reached_limit();
    }
  }
  // Supposed to be called regularly during a marking step as
  // it checks a bunch of conditions that might cause the marking step
  // to abort
  // Return true if the marking step should continue. Otherwise, return false to abort
  bool regular_clock_call();

  // Set abort flag if regular_clock_call() check fails
  inline void abort_marking_if_regular_check_fail();

  // Test whether obj might have already been passed over by the
  // mark bitmap scan, and so needs to be pushed onto the mark stack.
  bool is_below_finger(oop obj, HeapWord* global_finger) const;

  template<bool scan> void process_grey_task_entry(G1TaskQueueEntry task_entry);
public:
  // Apply the closure on the given area of the objArray. Return the number of words
  // scanned.
  inline size_t scan_objArray(objArrayOop obj, MemRegion mr);
  // Resets the task; should be called right at the beginning of a marking phase.
  void reset(G1CMBitMap* mark_bitmap);
  // Clears all the fields that correspond to a claimed region.
  void clear_region_fields();

  // The main method of this class which performs a marking step
  // trying not to exceed the given duration. However, it might exit
  // prematurely, according to some conditions (i.e. SATB buffers are
  // available for processing).
  void do_marking_step(double target_ms,
                       bool do_termination,
                       bool is_serial);

  // These two calls start and stop the timer
  void record_start_time() {
    _elapsed_time_ms = os::elapsedTime() * 1000.0;
  }
  void record_end_time() {
    _elapsed_time_ms = os::elapsedTime() * 1000.0 - _elapsed_time_ms;
  }

  // Returns the worker ID associated with this task.
  uint worker_id() { return _worker_id; }

  // From TerminatorTerminator. It determines whether this task should
  // exit the termination protocol after it's entered it.
  virtual bool should_exit_termination();

  // Resets the local region fields after a task has finished scanning a
  // region; or when they have become stale as a result of the region
  // being evacuated.
  void giveup_current_region();

  HeapWord* finger()            { return _finger; }

  bool has_aborted()            { return _has_aborted; }
  void set_has_aborted()        { _has_aborted = true; }
  void clear_has_aborted()      { _has_aborted = false; }

  void set_cm_oop_closure(G1CMOopClosure* cm_oop_closure);

  // Increment the number of references this task has visited.
  void increment_refs_reached() { ++_refs_reached; }

  // Grey the object by marking it.  If not already marked, push it on
  // the local queue if below the finger. obj is required to be below its region's TAMS.
  // Returns whether there has been a mark to the bitmap.
  inline bool make_reference_grey(oop obj);

  // Grey the object (by calling make_grey_reference) if required,
  // e.g. obj is below its containing region's TAMS.
  // Precondition: obj is a valid heap object.
  // Returns true if the reference caused a mark to be set in the marking bitmap.
  template <class T>
  inline bool deal_with_reference(T* p);

  // Scans an object and visits its children.
  inline void scan_task_entry(G1TaskQueueEntry task_entry);

  // Pushes an object on the local queue.
  inline void push(G1TaskQueueEntry task_entry);

  // Move entries to the global stack.
  void move_entries_to_global_stack();
  // Move entries from the global stack, return true if we were successful to do so.
  bool get_entries_from_global_stack();

  // Pops and scans objects from the local queue. If partially is
  // true, then it stops when the queue size is of a given limit. If
  // partially is false, then it stops when the queue is empty.
  void drain_local_queue(bool partially);
  // Moves entries from the global stack to the local queue and
  // drains the local queue. If partially is true, then it stops when
  // both the global stack and the local queue reach a given size. If
  // partially if false, it tries to empty them totally.
  void drain_global_stack(bool partially);
  // Keeps picking SATB buffers and processing them until no SATB
  // buffers are available.
  void drain_satb_buffers();

  // Moves the local finger to a new location
  inline void move_finger_to(HeapWord* new_finger) {
    assert(new_finger >= _finger && new_finger < _region_limit, "invariant");
    _finger = new_finger;
  }

  G1CMTask(uint worker_id,
           G1ConcurrentMark *cm,
           G1CMTaskQueue* task_queue,
           G1RegionMarkStats* mark_stats);

  inline void update_liveness(oop const obj, size_t const obj_size);

  inline void inc_incoming_refs(oop const obj);

  // Clear (without flushing) the mark cache entry for the given region.
  void clear_mark_stats_cache(uint region_idx);
  // Evict the whole statistics cache into the global statistics. Returns the
  // number of cache hits and misses so far.
  Pair<size_t, size_t> flush_mark_stats_cache();
  // Prints statistics associated with this task
  void print_stats();
};

// Class that's used to to print out per-region liveness
// information. It's currently used at the end of marking and also
// after we sort the old regions at the end of the cleanup operation.
class G1PrintRegionLivenessInfoClosure : public G1HeapRegionClosure {
  // Accumulators for these values.
  size_t _total_used_bytes;
  size_t _total_capacity_bytes;
  size_t _total_live_bytes;

  // Accumulator for the remembered set size
  size_t _total_remset_bytes;

  // Accumulator for code roots memory size
  size_t _total_code_roots_bytes;

  static double bytes_to_mb(size_t val) {
    return (double) val / (double) M;
  }

  void do_cset_groups();

public:
  // The header and footer are printed in the constructor and
  // destructor respectively.
  G1PrintRegionLivenessInfoClosure(const char* phase_name);
  virtual bool do_heap_region(G1HeapRegion* r);
  ~G1PrintRegionLivenessInfoClosure();
};
#endif // SHARE_GC_G1_G1CONCURRENTMARK_HPP
