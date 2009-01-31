#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)jvmtiExport.hpp	1.95 07/05/05 17:06:37 JVM"
#endif
/*
 * Copyright 1998-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *  
 */

#ifndef _JAVA_JVMTIEXPORT_H_
#define _JAVA_JVMTIEXPORT_H_

// Forward declarations

class JvmtiEventControllerPrivate;
class JvmtiManageCapabilities;
class JvmtiEnv;
class JvmtiThreadState;
class AttachOperation;

// This class contains the JVMTI interface for the rest of hotspot.
//
class JvmtiExport : public AllStatic {
 private:
  static int         _field_access_count;
  static int         _field_modification_count;

  static bool        _can_get_source_debug_extension;
  static bool        _can_examine_or_deopt_anywhere;
  static bool        _can_maintain_original_method_order;
  static bool        _can_post_interpreter_events;
  static bool        _can_hotswap_or_post_breakpoint;
  static bool        _can_modify_any_class;
  static bool	     _can_walk_any_space;
  static bool        _can_access_local_variables;
  static bool        _can_post_exceptions;
  static bool        _can_post_breakpoint;
  static bool        _can_post_field_access;
  static bool        _can_post_field_modification;
  static bool        _can_post_method_entry;
  static bool        _can_post_method_exit;
  static bool        _can_pop_frame;
  static bool        _can_force_early_return;

  static bool        _should_post_single_step;
  static bool        _should_post_field_access;
  static bool        _should_post_field_modification;
  static bool        _should_post_class_load;
  static bool        _should_post_class_prepare;
  static bool        _should_post_class_unload;
  static bool        _should_post_class_file_load_hook;
  static bool        _should_post_native_method_bind;
  static bool        _should_post_compiled_method_load;
  static bool        _should_post_compiled_method_unload;
  static bool        _should_post_dynamic_code_generated;
  static bool        _should_post_monitor_contended_enter;
  static bool        _should_post_monitor_contended_entered;
  static bool        _should_post_monitor_wait;
  static bool        _should_post_monitor_waited;
  static bool        _should_post_data_dump;
  static bool        _should_post_garbage_collection_start;
  static bool        _should_post_garbage_collection_finish;
  static bool        _should_post_thread_life;
  static bool	     _should_post_object_free;
  static bool	     _should_post_resource_exhausted;
  static bool        _should_clean_up_heap_objects;
  static bool        _should_post_vm_object_alloc;    


  // these should only be called by the friend class
  friend class JvmtiManageCapabilities;
  inline static void set_can_get_source_debug_extension(bool on)       { _can_get_source_debug_extension = (on != 0); }
  inline static void set_can_examine_or_deopt_anywhere(bool on)        { _can_examine_or_deopt_anywhere = (on != 0); }
  inline static void set_can_maintain_original_method_order(bool on)   { _can_maintain_original_method_order = (on != 0); }
  inline static void set_can_post_interpreter_events(bool on)          { _can_post_interpreter_events = (on != 0); }
  inline static void set_can_hotswap_or_post_breakpoint(bool on)       { _can_hotswap_or_post_breakpoint = (on != 0); }
  inline static void set_can_modify_any_class(bool on)                 { _can_modify_any_class = (on != 0); }
  inline static void set_can_walk_any_space(bool on)		       { _can_walk_any_space = (on != 0); }
  inline static void set_can_access_local_variables(bool on)           { _can_access_local_variables = (on != 0); }
  inline static void set_can_post_exceptions(bool on)                  { _can_post_exceptions = (on != 0); }
  inline static void set_can_post_breakpoint(bool on)                  { _can_post_breakpoint = (on != 0); }
  inline static void set_can_post_field_access(bool on)                { _can_post_field_access = (on != 0); }
  inline static void set_can_post_field_modification(bool on)          { _can_post_field_modification = (on != 0); }
  inline static void set_can_post_method_entry(bool on)                { _can_post_method_entry = (on != 0); }
  inline static void set_can_post_method_exit(bool on)                 { _can_post_method_exit = (on != 0); }
  inline static void set_can_pop_frame(bool on)                        { _can_pop_frame = (on != 0); }
  inline static void set_can_force_early_return(bool on)               { _can_force_early_return = (on != 0); }

  // these should only be called by the friend class
  friend class JvmtiEventControllerPrivate;
  inline static void set_should_post_single_step(bool on)              { _should_post_single_step = on; }
  inline static void set_should_post_field_access(bool on)             { _should_post_field_access = on; }
  inline static void set_should_post_field_modification(bool on)       { _should_post_field_modification = on; }
  inline static void set_should_post_class_load(bool on)               { _should_post_class_load = on; }
  inline static void set_should_post_class_prepare(bool on)            { _should_post_class_prepare = on; }
  inline static void set_should_post_class_unload(bool on)             { _should_post_class_unload = on; }
  inline static void set_should_post_class_file_load_hook(bool on)     { _should_post_class_file_load_hook = on;  }   
  inline static void set_should_post_native_method_bind(bool on)       { _should_post_native_method_bind = on; }
  inline static void set_should_post_compiled_method_load(bool on)     { _should_post_compiled_method_load = on; }
  inline static void set_should_post_compiled_method_unload(bool on)   { _should_post_compiled_method_unload = on; }
  inline static void set_should_post_dynamic_code_generated(bool on)   { _should_post_dynamic_code_generated = on;  }   
  inline static void set_should_post_monitor_contended_enter(bool on)  { _should_post_monitor_contended_enter = on; }
  inline static void set_should_post_monitor_contended_entered(bool on){ _should_post_monitor_contended_entered = on; }
  inline static void set_should_post_monitor_wait(bool on)             { _should_post_monitor_wait = on; }
  inline static void set_should_post_monitor_waited(bool on)           { _should_post_monitor_waited = on; }
  inline static void set_should_post_garbage_collection_start(bool on) { _should_post_garbage_collection_start = on; }
  inline static void set_should_post_garbage_collection_finish(bool on){ _should_post_garbage_collection_finish = on; }
  inline static void set_should_post_data_dump(bool on)                { _should_post_data_dump = on;  }   
  inline static void set_should_post_object_free(bool on)	       { _should_post_object_free = on; }
  inline static void set_should_post_resource_exhausted(bool on)       { _should_post_resource_exhausted = on; }
  inline static void set_should_post_vm_object_alloc(bool on)	       { _should_post_vm_object_alloc = on; }    

  inline static void set_should_post_thread_life(bool on)              { _should_post_thread_life = on; }
  inline static void set_should_clean_up_heap_objects(bool on)         { _should_clean_up_heap_objects = on; }

  enum {
    JVMTI_VERSION_MASK   = 0x70000000,
    JVMTI_VERSION_VALUE  = 0x30000000,
    JVMDI_VERSION_VALUE  = 0x20000000
  };

  static void post_field_modification(JavaThread *thread, methodOop method, address location, 
                                      KlassHandle field_klass, Handle object, jfieldID field,
                                      char sig_type, jvalue *value);


 private:
  // CompiledMethodUnload events are reported from the VM thread so they
  // are collected in lists (of jmethodID/addresses) and the events are posted later
  // from threads posting CompieldMethodLoad or DynamicCodeGenerated events.
  static bool _have_pending_compiled_method_unload_events;		
  static GrowableArray<jmethodID>* _pending_compiled_method_unload_method_ids;	
  static GrowableArray<const void *>* _pending_compiled_method_unload_code_begins;	
  static JavaThread* _current_poster;

  // tests if there are CompiledMethodUnload events pending
  inline static bool have_pending_compiled_method_unload_events() { 
    return _have_pending_compiled_method_unload_events; 
  }

  // posts any pending CompiledMethodUnload events. 
  static void post_pending_compiled_method_unload_events();

  // posts a DynamicCodeGenerated event (internal/private implementation). 
  // The public post_dynamic_code_generated* functions make use of the
  // internal implementation.
  static void post_dynamic_code_generated_internal(const char *name, const void *code_begin, const void *code_end);


  // GenerateEvents support to allow posting of CompiledMethodLoad and
  // DynamicCodeGenerated events for a given environment.
  friend class JvmtiCodeBlobEvents;

  static void post_compiled_method_load(JvmtiEnv* env, const jmethodID method, const jint length, 
				        const void *code_begin, const jint map_length, 
					const jvmtiAddrLocationMap* map);
  static void post_dynamic_code_generated(JvmtiEnv* env, const char *name, const void *code_begin, 
					  const void *code_end);

  // The RedefineClasses() API breaks some invariants in the "regular"
  // system. For example, there are sanity checks when GC'ing nmethods
  // that require the containing class to be unloading. However, when a
  // method is redefined, the old method and nmethod can become GC'able
  // without the containing class unloading. The state of becoming
  // GC'able can be asynchronous to the RedefineClasses() call since
  // the old method may still be running and cannot be GC'ed until
  // after all old invocations have finished. Additionally, a method
  // that has not been redefined may have an nmethod that depends on
  // the redefined method. The dependent nmethod will get deopted in
  // this case and may also be GC'able without the containing class
  // being unloaded.
  //
  // This flag indicates whether RedefineClasses() has ever redefined
  // one or more classes during the lifetime of the VM. The flag should
  // only be set by the friend class and can be queried by other sub
  // systems as needed to relax invariant checks.
  static bool _has_redefined_a_class;
  friend class VM_RedefineClasses;
  inline static void set_has_redefined_a_class() {
    _has_redefined_a_class = true;
  }

  // Flag to indicate if the compiler has recorded all dependencies. When the
  // can_redefine_classes capability is enabled in the OnLoad phase then the compiler
  // records all dependencies from startup. However if the capability is first
  // enabled some time later then the dependencies recorded by the compiler
  // are incomplete. This flag is used by RedefineClasses to know if the 
  // dependency information is complete or not.
  static bool _all_dependencies_are_recorded;

 public:  
  inline static bool has_redefined_a_class() {
    return _has_redefined_a_class;
  }

  inline static bool all_dependencies_are_recorded() {
    return _all_dependencies_are_recorded;
  }

  inline static void set_all_dependencies_are_recorded(bool on) {
    _all_dependencies_are_recorded = (on != 0);
  }


  // let JVMTI know that the JVM_OnLoad code is running
  static void enter_onload_phase();

  // let JVMTI know that the VM isn't up yet (and JVM_OnLoad code isn't running)
  static void enter_primordial_phase();

  // let JVMTI know that the VM isn't up yet but JNI is live
  static void enter_start_phase();

  // let JVMTI know that the VM is fully up and running now
  static void enter_live_phase();

  // ------ can_* conditions (below) are set at OnLoad and never changed ------------

  inline static bool can_get_source_debug_extension()             { return _can_get_source_debug_extension; }

  // BP, expression stack, hotswap, interp_only, local_var, monitor info
  inline static bool can_examine_or_deopt_anywhere()              { return _can_examine_or_deopt_anywhere; }

  // JVMDI spec requires this, does this matter for JVMTI?
  inline static bool can_maintain_original_method_order()         { return _can_maintain_original_method_order; }

  // any of single-step, method-entry/exit, frame-pop, and field-access/modification
  inline static bool can_post_interpreter_events()                { return _can_post_interpreter_events; }

  inline static bool can_hotswap_or_post_breakpoint()             { return _can_hotswap_or_post_breakpoint; }

  inline static bool can_modify_any_class()                       { return _can_modify_any_class; }

  inline static bool can_walk_any_space()			  { return _can_walk_any_space; }

  // can retrieve frames, set/get local variables or hotswap
  inline static bool can_access_local_variables()                 { return _can_access_local_variables; }

  // throw or catch
  inline static bool can_post_exceptions()                        { return _can_post_exceptions; }

  inline static bool can_post_breakpoint()                        { return _can_post_breakpoint; }
  inline static bool can_post_field_access()                      { return _can_post_field_access; }
  inline static bool can_post_field_modification()                { return _can_post_field_modification; }
  inline static bool can_post_method_entry()                      { return _can_post_method_entry; }
  inline static bool can_post_method_exit()                       { return _can_post_method_exit; }
  inline static bool can_pop_frame()                              { return _can_pop_frame; }
  inline static bool can_force_early_return()                     { return _can_force_early_return; }


  // ------ the below maybe don't have to be (but are for now) fixed conditions here ------------
  // any events can be enabled
  inline static bool should_post_thread_life()                   { return _should_post_thread_life; }


  // ------ DYNAMIC conditions here ------------

  inline static bool should_post_single_step()                    { return _should_post_single_step; }
  inline static bool should_post_field_access()                   { return _should_post_field_access; }
  inline static bool should_post_field_modification()             { return _should_post_field_modification; }
  inline static bool should_post_class_load()                     { return _should_post_class_load; }
  inline static bool should_post_class_prepare()                  { return _should_post_class_prepare; }
  inline static bool should_post_class_unload()                   { return _should_post_class_unload; }
  inline static bool should_post_class_file_load_hook()           { return _should_post_class_file_load_hook; }
  inline static bool should_post_native_method_bind()             { return _should_post_native_method_bind; }
  inline static bool should_post_compiled_method_load()           { return _should_post_compiled_method_load; }
  inline static bool should_post_compiled_method_unload()         { return _should_post_compiled_method_unload; }
  inline static bool should_post_dynamic_code_generated()         { return _should_post_dynamic_code_generated; }
  inline static bool should_post_monitor_contended_enter()        { return _should_post_monitor_contended_enter; }
  inline static bool should_post_monitor_contended_entered()      { return _should_post_monitor_contended_entered; }
  inline static bool should_post_monitor_wait()                   { return _should_post_monitor_wait; }
  inline static bool should_post_monitor_waited()                 { return _should_post_monitor_waited; }
  inline static bool should_post_data_dump()                      { return _should_post_data_dump; }
  inline static bool should_post_garbage_collection_start()       { return _should_post_garbage_collection_start; }
  inline static bool should_post_garbage_collection_finish()      { return _should_post_garbage_collection_finish; }
  inline static bool should_post_object_free()			  { return _should_post_object_free; }
  inline static bool should_post_resource_exhausted()		  { return _should_post_resource_exhausted; }
  inline static bool should_post_vm_object_alloc()		  { return _should_post_vm_object_alloc; }

  // we are holding objects on the heap - need to talk to GC - e.g. breakpoint info
  inline static bool should_clean_up_heap_objects()               { return _should_clean_up_heap_objects; }

  // field access management
  static address  get_field_access_count_addr();

  // field modification management
  static address  get_field_modification_count_addr();

  // -----------------

  static bool is_jvmti_version(jint version)                      { return (version & JVMTI_VERSION_MASK) == JVMTI_VERSION_VALUE; }
  static bool is_jvmdi_version(jint version)                      { return (version & JVMTI_VERSION_MASK) == JVMDI_VERSION_VALUE; }
  static jint get_jvmti_interface(JavaVM *jvm, void **penv, jint version);
  

  // single stepping management methods
  static void at_single_stepping_point(JavaThread *thread, methodOop method, address location);
  static void expose_single_stepping(JavaThread *thread);
  static bool hide_single_stepping(JavaThread *thread);

  // Methods that notify the debugger that something interesting has happened in the VM.
  static void post_vm_start              (); 
  static void post_vm_initialized        (); 
  static void post_vm_death              ();
  
  static void post_single_step           (JavaThread *thread, methodOop method, address location);
  static void post_raw_breakpoint        (JavaThread *thread, methodOop method, address location);
  
  static void post_exception_throw       (JavaThread *thread, methodOop method, address location, oop exception);
  static void notice_unwind_due_to_exception (JavaThread *thread, methodOop method, address location, oop exception, bool in_handler_frame);

  static oop jni_GetField_probe          (JavaThread *thread, jobject jobj,
    oop obj, klassOop klass, jfieldID fieldID, bool is_static);
  static oop jni_GetField_probe_nh       (JavaThread *thread, jobject jobj,
    oop obj, klassOop klass, jfieldID fieldID, bool is_static);
  static void post_field_access_by_jni   (JavaThread *thread, oop obj,
    klassOop klass, jfieldID fieldID, bool is_static);
  static void post_field_access          (JavaThread *thread, methodOop method,
    address location, KlassHandle field_klass, Handle object, jfieldID field);
  static oop jni_SetField_probe          (JavaThread *thread, jobject jobj,
    oop obj, klassOop klass, jfieldID fieldID, bool is_static, char sig_type,
    jvalue *value);
  static oop jni_SetField_probe_nh       (JavaThread *thread, jobject jobj,
    oop obj, klassOop klass, jfieldID fieldID, bool is_static, char sig_type,
    jvalue *value);
  static void post_field_modification_by_jni(JavaThread *thread, oop obj,
    klassOop klass, jfieldID fieldID, bool is_static, char sig_type,
    jvalue *value);
  static void post_raw_field_modification(JavaThread *thread, methodOop method,
    address location, KlassHandle field_klass, Handle object, jfieldID field,
    char sig_type, jvalue *value);

  static void post_method_entry          (JavaThread *thread, methodOop method, frame current_frame);
  static void post_method_exit           (JavaThread *thread, methodOop method, frame current_frame);

  static void post_class_load            (JavaThread *thread, klassOop klass);
  static void post_class_unload          (klassOop klass);
  static void post_class_prepare         (JavaThread *thread, klassOop klass);
  
  static void post_thread_start          (JavaThread *thread);
  static void post_thread_end            (JavaThread *thread);

  static void post_class_file_load_hook(symbolHandle h_name, Handle class_loader, 
                                        Handle h_protection_domain, 
                                        unsigned char **data_ptr, unsigned char **end_ptr, 
                                        unsigned char **cached_data_ptr, 
                                        jint *cached_length_ptr);
  static void post_native_method_bind(methodOop method, address* function_ptr);
  static void post_compiled_method_load(nmethod *nm);
  static void post_dynamic_code_generated(const char *name, const void *code_begin, const void *code_end);

  // used at a safepoint to post a CompiledMethodUnload event
  static void post_compiled_method_unload_at_safepoint(jmethodID mid, const void *code_begin);

  // similiar to post_dynamic_code_generated except that it can be used to
  // post a DynamicCodeGenerated event while holding locks in the VM. Any event
  // posted using this function is recorded by the enclosing event collector
  // -- JvmtiDynamicCodeEventCollector.
  static void post_dynamic_code_generated_while_holding_locks(const char* name, address code_begin, address code_end); 

  static void post_garbage_collection_finish();
  static void post_garbage_collection_start();
  static void post_data_dump();
  static void post_monitor_contended_enter(JavaThread *thread, ObjectMonitor *obj_mntr);
  static void post_monitor_contended_entered(JavaThread *thread, ObjectMonitor *obj_mntr);
  static void post_monitor_wait(JavaThread *thread, oop obj, jlong timeout);
  static void post_monitor_waited(JavaThread *thread, ObjectMonitor *obj_mntr, jboolean timed_out);
  static void post_object_free(JvmtiEnv* env, jlong tag);
  static void post_resource_exhausted(jint resource_exhausted_flags, const char* detail);
  static void record_vm_internal_object_allocation(oop object);
  // Post objects collected by vm_object_alloc_event_collector.
  static void post_vm_object_alloc(JavaThread *thread, oop object);  
  // Collects vm internal objects for later event posting.
  inline static void vm_object_alloc_event_collector(oop object) {
    if (should_post_vm_object_alloc()) {
      record_vm_internal_object_allocation(object);
    }      
  }

  static void cleanup_thread             (JavaThread* thread);  

  static void oops_do(OopClosure* f);

  static void transition_pending_onload_raw_monitors();

  // attach support
  static jint load_agent_library(AttachOperation* op, outputStream* out);

  // SetNativeMethodPrefix support
  static char** get_all_native_method_prefixes(int* count_ptr);

  // call after CMS has completed referencing processing
  static void cms_ref_processing_epilogue();
};

// Support class used by JvmtiDynamicCodeEventCollector and others. It
// describes a single code blob by name and address range.
class JvmtiCodeBlobDesc : public CHeapObj {
 private:
  char _name[64];
  address _code_begin;
  address _code_end;

 public:
  JvmtiCodeBlobDesc(const char *name, address code_begin, address code_end) {
    assert(name != NULL, "all code blobs must be named");
    strncpy(_name, name, sizeof(_name));
    _name[sizeof(_name)-1] = '\0';
    _code_begin = code_begin;
    _code_end = code_end;
  }
  char* name()			{ return _name; }
  address code_begin()		{ return _code_begin; }
  address code_end()		{ return _code_end; }
};

// JvmtiEventCollector is a helper class to setup thread for
// event collection.
class JvmtiEventCollector : public StackObj {
 private:
  JvmtiEventCollector* _prev;  // Save previous one to support nested event collector.
    
 public:
  void setup_jvmti_thread_state(); // Set this collector in current thread.
  void unset_jvmti_thread_state(); // Reset previous collector in current thread.
  virtual bool is_dynamic_code_event()   { return false; }
  virtual bool is_vm_object_alloc_event(){ return false; }
  JvmtiEventCollector *get_prev()        { return _prev; }
};

// A JvmtiDynamicCodeEventCollector is a helper class for the JvmtiExport
// interface. It collects "dynamic code generated" events that are posted
// while holding locks. When the event collector goes out of scope the
// events will be posted.
//
// Usage :-
//
// {
//   JvmtiDynamicCodeEventCollector event_collector;
//   :
//   { MutexLocker ml(...)
//     :
//     JvmtiExport::post_dynamic_code_generated_while_holding_locks(...)
//   }
//   // event collector goes out of scope => post events to profiler.
// }

class JvmtiDynamicCodeEventCollector : public JvmtiEventCollector {
 private: 
  GrowableArray<JvmtiCodeBlobDesc*>* _code_blobs;	    // collected code blob events

  friend class JvmtiExport;
  void register_stub(const char* name, address start, address end);

 public:
  JvmtiDynamicCodeEventCollector();
  ~JvmtiDynamicCodeEventCollector();
  bool is_dynamic_code_event()   { return true; }
    
};

// Used to record vm internally allocated object oops and post 
// vm object alloc event for objects visible to java world.
// Constructor enables JvmtiThreadState flag and all vm allocated
// objects are recorded in a growable array. When destructor is
// called the vm object alloc event is posted for each objects
// visible to java world.
// See jvm.cpp file for its usage.
//
class JvmtiVMObjectAllocEventCollector : public JvmtiEventCollector {
 private:
  GrowableArray<oop>* _allocated; // field to record vm internally allocated object oop.
  bool _enable;                   // This flag is enabled in constructor and disabled
                                  // in destructor before posting event. To avoid
                                  // collection of objects allocated while running java code inside
                                  // agent post_vm_object_alloc() event handler.

  //GC support
  void oops_do(OopClosure* f);
    
  friend class JvmtiExport;
  // Record vm allocated object oop. 
  inline void record_allocation(oop obj);

  //GC support
  static void oops_do_for_all_threads(OopClosure* f);
    
 public:
  JvmtiVMObjectAllocEventCollector(); 
  ~JvmtiVMObjectAllocEventCollector();
  bool is_vm_object_alloc_event()   { return true; }

  bool is_enabled()		    { return _enable; }
  void set_enabled(bool on)	    { _enable = on; }
};



// Marker class to disable the posting of VMObjectAlloc events
// within its scope.
//
// Usage :-
//
// {
//   NoJvmtiVMObjectAllocMark njm;
//   :
//   // VMObjAlloc event will not be posted
//   JvmtiExport::vm_object_alloc_event_collector(obj);
//   :
// }

class NoJvmtiVMObjectAllocMark : public StackObj {
 private:
  // enclosing collector if enabled, NULL otherwise
  JvmtiVMObjectAllocEventCollector *_collector;	    
  
  bool was_enabled()	{ return _collector != NULL; }  

 public:
  NoJvmtiVMObjectAllocMark();
  ~NoJvmtiVMObjectAllocMark();
};


// Base class for reporting GC events to JVMTI. 
class JvmtiGCMarker : public StackObj {
 private:
  bool _full;				// marks a "full" GC
  unsigned int _invocation_count;	// GC invocation count
 protected:
  JvmtiGCMarker(bool full);		// protected 
  ~JvmtiGCMarker();			// protected
};


// Support class used to report GC events to JVMTI. The class is stack
// allocated and should be placed in the doit() implementation of all
// vm operations that do a stop-the-world GC for failed allocation.
//
// Usage :-
//
// void VM_GenCollectForAllocation::doit() {
//   JvmtiGCForAllocationMarker jgcm;
//   :
// }
// 
// If jvmti is not enabled the constructor and destructor is essentially
// a no-op (no overhead). 
//
class JvmtiGCForAllocationMarker : public JvmtiGCMarker {
 public:
  JvmtiGCForAllocationMarker() : JvmtiGCMarker(false) { 
  }
};

// Support class used to report GC events to JVMTI. The class is stack
// allocated and should be placed in the doit() implementation of all
// vm operations that do a "full" stop-the-world GC. This class differs
// from JvmtiGCForAllocationMarker in that this class assumes that a
// "full" GC will happen. 
//
// Usage :-
//
// void VM_GenCollectFull::doit() {
//   JvmtiGCFullMarker jgcm;
//   :
// }
//
class JvmtiGCFullMarker : public JvmtiGCMarker {
 public:
  JvmtiGCFullMarker() : JvmtiGCMarker(true) { 
  }
};


// JvmtiHideSingleStepping is a helper class for hiding
// internal single step events.
class JvmtiHideSingleStepping : public StackObj {
 private:
  bool         _single_step_hidden;
  JavaThread * _thread;

 public:
  JvmtiHideSingleStepping(JavaThread * thread) {
    assert(thread != NULL, "sanity check");

    _single_step_hidden = false;
    _thread = thread;
    if (JvmtiExport::should_post_single_step()) {
      _single_step_hidden = JvmtiExport::hide_single_stepping(_thread);
    }
  }

  ~JvmtiHideSingleStepping() {
    if (_single_step_hidden) {
      JvmtiExport::expose_single_stepping(_thread);
    }  
  }
};

#endif   /* _JAVA_JVMTIEXPORT_H_ */
