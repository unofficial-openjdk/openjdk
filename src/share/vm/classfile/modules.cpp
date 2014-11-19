/*
* Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "classfile/classLoader.hpp"
#include "classfile/classLoaderData.inline.hpp"
#include "classfile/javaAssertions.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/moduleEntry.hpp"
#include "classfile/modules.hpp"
#include "classfile/packageEntry.hpp"
#include "classfile/stringTable.hpp"
#include "classfile/vmSymbols.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/objArrayKlass.hpp"
#include "runtime/arguments.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/reflection.hpp"
#include "utilities/utf8.hpp"


static bool verify_module_name(char *module_name) {
  if (module_name == NULL) return false;
  int len = (int)strlen(module_name);
  return (len <= Symbol::max_length() &&
    UTF8::is_legal_utf8((unsigned char *)module_name, len, false) &&
    ClassFileParser::verify_unqualified_name(module_name, len,
    ClassFileParser::LegalModule));
}

static bool verify_package_name(char *package_name) {
  if (package_name == NULL) return false;
  int len = (int)strlen(package_name);
  return (len <= Symbol::max_length() &&
    UTF8::is_legal_utf8((unsigned char *)package_name, len, false) &&
    ClassFileParser::verify_unqualified_name(package_name, len,
    ClassFileParser::LegalClass));
}

static ModuleEntryTable* get_module_entry_table(Handle h_loader, TRAPS) {
  h_loader = Handle(THREAD, java_lang_ClassLoader::non_reflection_class_loader(h_loader()));
  ClassLoaderData *loader_cld = SystemDictionary::register_loader(h_loader, CHECK_NULL);
  return loader_cld != NULL ? loader_cld->modules() : NULL;
}

static PackageEntryTable* get_package_entry_table(Handle h_loader, TRAPS) {
  h_loader = Handle(THREAD, java_lang_ClassLoader::non_reflection_class_loader(h_loader()));
  ClassLoaderData *loader_cld = SystemDictionary::register_loader(h_loader, CHECK_NULL);
  return loader_cld != NULL ? loader_cld->packages() : NULL;
}

static void add_to_boot_loader_list(char *module_name, TRAPS) {
  assert(strcmp(module_name, "java.base") != 0, "Unexpected java.base module name");
  char file_sep = os::file_separator()[0];
  size_t module_len = strlen(module_name);

  // If -Xoverride is set then add path <override-dir>/module_name.
  char* prefix_path = NULL;
  if (Arguments::override_dir() != NULL) {
    size_t len = strlen(Arguments::override_dir()) + module_len + 2;
    prefix_path = NEW_C_HEAP_ARRAY(char, len, mtInternal);
    jio_snprintf(prefix_path, len, "%s%c%s", Arguments::override_dir(), file_sep, module_name);
  }

  // If bootmodules.jimage does not exist then assume exploded
  // form ${java.home}/modules/$MODULE
  char* path = NULL;
  if (!ClassLoader::has_bootmodules_jimage()) {
    const char* home = Arguments::get_java_home();
    size_t len = strlen(home) + module_len + 32;
    path = NEW_C_HEAP_ARRAY(char, len, mtInternal);
    jio_snprintf(path, len, "%s%cmodules%c%s", home, file_sep, file_sep, module_name);
    struct stat st;
    // See if exploded module path exists.
    if ((os::stat(path, &st) != 0)) {
      FREE_C_HEAP_ARRAY(char, path, mtInternal);
      path = NULL;
    }
  }

  if (prefix_path != NULL || path != NULL) {
    HandleMark hm;
    Handle loader_lock = Handle(THREAD, SystemDictionary::system_loader_lock());
    ObjectLocker ol(loader_lock, THREAD);

    if (prefix_path != NULL) {
      if (TraceClassLoading) tty->print_cr("[Opened -Xoverride %s]", prefix_path);
      ClassLoader::add_to_list(prefix_path);
    }
    if (path != NULL) {
      if (TraceClassLoading) tty->print_cr("[Opened %s]", path);
      ClassLoader::add_to_list(path);
    }
  }
}

static ModuleEntry* get_module_entry(jobject module, TRAPS) {
  Handle h_module(THREAD, JNIHandles::resolve(module));
  oop loader = java_lang_reflect_Module::loader(h_module());
  Handle h_loader = Handle(loader);
  ModuleEntryTable* module_table = get_module_entry_table(h_loader, CHECK_NULL);
  return module_table != NULL ? module_table->lookup_only(h_module()) : NULL;
}

static PackageEntry* get_package_entry(ModuleEntry* module_entry, jstring package, TRAPS) {
  ResourceMark rm;
  if (package == NULL) return NULL;
  const char *package_name = java_lang_String::as_utf8_string(JNIHandles::resolve_non_null(package));
  if (package_name == NULL) return NULL;
  Symbol *pkg_symbol = SymbolTable::new_symbol(package_name, CHECK_NULL);  // TBD use new_permanent_symbol()?
  PackageEntryTable* package_entry_table = module_entry->loader()->packages();
  return package_entry_table != NULL ? package_entry_table->lookup_only(pkg_symbol) : NULL;
}


jobject modules::define_module(JNIEnv *env, jstring name, jobject loader, jobjectArray packages) {
  ResourceMark rm;
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (name == NULL) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
      "Null module name");
  }
  char *module_name =
    java_lang_String::as_utf8_string(JNIHandles::resolve_non_null(name));

  if (TraceModules) {
    tty->print_cr("[JVM_DefineModule: Start of definition processing for module %s]", module_name);
  }

  if (!verify_module_name(module_name)) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
      err_msg("Invalid module name: %s", module_name));
  }
  if (strcmp(module_name, vmSymbols::java_base()->as_C_string()) == 0) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
      "Module java.base is already defined");
  }

  objArrayOop packages_oop = objArrayOop(JNIHandles::resolve_non_null(packages));
  objArrayHandle packages_h(THREAD, packages_oop);
  int num_packages = packages_h->length();

  // Check that the list of packages has no duplicates and that the
  // packages are syntactically ok.
  GrowableArray<Symbol *>* pkg_list = new GrowableArray<Symbol *>(num_packages); // TBD does this need explicit deletion?
  for (int x = 0; x < num_packages; x++) {
    oop string_obj = packages_h->obj_at(x);

    if (string_obj == NULL || !string_obj->is_a(SystemDictionary::String_klass())) {
      THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
        err_msg("Bad package name for module: %s", module_name));
    }
    char *package_name = java_lang_String::as_utf8_string(string_obj);
    if (!verify_package_name(package_name)) {
      THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
        err_msg("Invalid package name: %s for module: %s",
        package_name, module_name));
    }
    Symbol *pkg_symbol = SymbolTable::new_symbol(package_name, CHECK_NULL);
    // append_if_missing() returns FALSE if entry already exists.
    if (!pkg_list->append_if_missing(pkg_symbol)) {
      THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
        err_msg("Duplicate package name: %s for module %s",
        package_name, module_name));
    }
  }

  Handle h_loader(THREAD, JNIHandles::resolve(loader));
  // Check that loader is a subclass of java.lang.ClassLoader.
  if (loader != NULL) {
    if (!java_lang_ClassLoader::is_subclass(h_loader->klass())) {
      THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
        "Class loader is not a subclass of java.lang.ClassLoader");
    }
  }

  ModuleEntryTable* module_table = get_module_entry_table(h_loader, CHECK_NULL);
  if (module_table == NULL)
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
    err_msg("Loader is invalid for module: %s", module_name));

  // Create symbol* entry for module name.
  Symbol *module_symbol = SymbolTable::new_symbol(module_name, CHECK_NULL);  // TBD: use new_permanent_symbol()?

  // Create the java.lang.reflect.Module object.
  Handle h_name(THREAD, JNIHandles::resolve_non_null(name));
  Handle jlrM_handle = java_lang_reflect_Module::create(h_loader, h_name,
    CHECK_NULL);
  if (jlrM_handle.is_null()) {
    THROW_MSG_NULL(vmSymbols::java_lang_InternalError(),
      err_msg("java.lang.reflect.Module creation failed for module: %s",
      module_name));
  }

  {
    MutexLocker ml(Module_lock, THREAD);

    PackageEntryTable* package_table = NULL;
    if (num_packages > 0) {
      package_table = get_package_entry_table(h_loader, CHECK_NULL);
      assert(package_table != NULL, "Missing package_table");

      // Check that none of the packages exist in the class loader's package table.
      for (int x = 0; x < pkg_list->length(); x++) {
        if (package_table->lookup_only(pkg_list->at(x))) {
          THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
            err_msg("Package %s for module %s already exists for class loader",
            pkg_list->at(x)->as_C_string(), module_name));
        }
      }
    }  // if (num_packages > 0)...

    // Add the module and its packages.

    // Create the entry for this module in the class loader's module entry table.
    ModuleEntry* module_entry =
      module_table->locked_create_entry(jlrM_handle(), module_symbol,
      ClassLoaderData::class_loader_data_or_null(h_loader()), CHECK_NULL);
    if (module_entry == NULL) {
      THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
        err_msg("Module %s is already defined", module_name));
    }

    if (TraceModules) {
      tty->print_cr("[JVM_DefineModule: creation of module = %s]", module_name);
    }

    // Add the packages.
    assert(pkg_list->length() == 0 || package_table != NULL, "Bad package table");
    for (int y = 0; y < pkg_list->length(); y++) {
      package_table->locked_create_entry(pkg_list->at(y), module_entry, CHECK_NULL);
      if (TraceModules || TracePackages) {
        tty->print_cr("[JVM_DefineModule: creation of package %s for module %s]",
          (pkg_list->at(y))->as_C_string(), module_name);
      }
    }
  }  // Release the lock

  if (loader == NULL) {
    add_to_boot_loader_list(module_name, CHECK_NULL);
  }

  // return the j.l.r.Module object for the Module.
  return JNIHandles::make_local(env, jlrM_handle());
}

void modules::add_module_exports(JNIEnv *env, jobject from_module, jstring package, jobject to_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (from_module == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "from_module is null");
  }
  ModuleEntry* from_module_entry = get_module_entry(from_module, CHECK);
  if (from_module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "from_module cannot be found");
  }
  ModuleEntry* to_module_entry;
  if (to_module == NULL)
    to_module_entry = NULL;  // It's the unnamed module.
  else {
    to_module_entry = get_module_entry(to_module, CHECK);
    if (to_module_entry == NULL) {
      THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
        "to_module is invalid");
    }
  }

  if (from_module_entry == to_module_entry) return;  // Trivial case.

  PackageEntry *package_entry = get_package_entry(from_module_entry, package,
    CHECK);
  if (package_entry == NULL) {
    ResourceMark rm;
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      err_msg("Package not found in from_module: %s",
      from_module_entry->name()->as_C_string()));
  }
  if (package_entry->module() != from_module_entry) {
    ResourceMark rm;
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      err_msg("Package: %s found in module %s, not in from_module: %s",
      package_entry->name()->as_C_string(),
      package_entry->module()->name()->as_C_string(),
      from_module_entry->name()->as_C_string()));
  }

  if (TraceModules) {
    ResourceMark rm;
    tty->print_cr("[JVM_AddModuleExports: package:module %s:%s is exported to module %s]",
      package_entry->name()->as_C_string(),
      from_module_entry->name()->as_C_string(),
      ((to_module_entry == NULL) ? NULL : to_module_entry->name()->as_C_string()));
  }

  package_entry->set_exported(to_module_entry, CHECK);
}

void modules::add_reads_module(JNIEnv *env, jobject from_module, jobject to_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (from_module == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "from_module is null");
  }
  if (to_module == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "to_module is null");
  }

  ModuleEntry* from_module_entry = get_module_entry(from_module, CHECK);
  if (from_module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "from_module is invalid");
  }
  ModuleEntry* to_module_entry = get_module_entry(to_module, CHECK);
  if (to_module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "to_module is invalid");
  }

  if (TraceModules) {
    ResourceMark rm;
    tty->print_cr("[JVM_AddReadsModule: Adding read from module %s to module %s]",
      from_module_entry->name()->as_C_string(),
      to_module_entry->name()->as_C_string());
  }

  if (from_module_entry == to_module_entry) return;
  from_module_entry->add_read(to_module_entry, CHECK);
}

jboolean modules::can_read_module(JNIEnv *env, jobject asking_module, jobject target_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  ModuleEntry* asking_module_entry = get_module_entry(asking_module, CHECK_false);
  if (asking_module_entry == NULL) {
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
      "asking_module is invalid", JNI_FALSE);
  }
  if (target_module == NULL) return JNI_TRUE;  // Unnamed module is always readable.
  ModuleEntry* target_module_entry = get_module_entry(target_module, CHECK_false);
  if (target_module_entry == NULL) {
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
      "target_module is invalid", JNI_FALSE);
  }

  if (TraceModules) {
    ResourceMark rm;
    tty->print_cr("[JVM_CanReadModule: module %s trying to read module %s, allowed = %d",
      asking_module_entry->name()->as_C_string(),
      target_module_entry->name()->as_C_string(),
      ((asking_module_entry == target_module_entry) ||
      (asking_module_entry->can_read(target_module_entry))));
  }

  if (asking_module_entry == target_module_entry) return JNI_TRUE;
  return asking_module_entry->can_read(target_module_entry);
}

jboolean modules::is_exported_to_module(JNIEnv *env, jobject from_module, jstring package, jobject to_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (from_module == NULL) {
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
      "from_module is null", JNI_FALSE);
  }
  ModuleEntry* from_module_entry = get_module_entry(from_module, CHECK_false);
  if (from_module_entry == NULL) {
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
      "from_module is invalid", JNI_FALSE);
  }
  ModuleEntry* to_module_entry;
  if (to_module == NULL) {
    to_module_entry = NULL;
  }
  else {
    to_module_entry = get_module_entry(to_module, CHECK_false);
    if (to_module == NULL) {
      THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
        "to_module is invalid", JNI_FALSE);
    }
  }
  PackageEntry *package_entry = get_package_entry(from_module_entry, package,
    CHECK_false);
  if (package_entry == NULL) {
    ResourceMark rm;
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
      err_msg("Package not found in from_module: %s",
      from_module_entry->name()->as_C_string()), JNI_FALSE);
  }
  if (package_entry->module() != from_module_entry) {
    ResourceMark rm;
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
      err_msg("Package: %s found in module %s, not in from_module: %s",
      package_entry->name()->as_C_string(),
      package_entry->module()->name()->as_C_string(),
      from_module_entry->name()->as_C_string()), JNI_FALSE);
  }

  if (TracePackages) {
    ResourceMark rm;
    tty->print_cr("[JVM_IsExportedToModule: package %s from module %s checking if exported to module %s, exported? = %d",
      package_entry->name()->as_C_string(),
      from_module_entry->name()->as_C_string(),
      to_module_entry->name()->as_C_string(),
      (package_entry->is_unqual_exported() ||
      (to_module != NULL && package_entry->is_qexported_to(to_module_entry)) ||
      (from_module_entry == to_module_entry)));
  }

  return (package_entry->is_unqual_exported() ||
    (to_module != NULL && package_entry->is_qexported_to(to_module_entry)) ||
    (from_module_entry == to_module_entry));

}

jobject modules::get_module(JNIEnv *env, jclass clazz) {
  oop mirror = JNIHandles::resolve_non_null(clazz);
  if (mirror == NULL || java_lang_Class::is_primitive(mirror) ||
    !UseModules) {
    if (TraceModules && !UseModules) {
      tty->print_cr("[JVM_GetModule: !UseModules, returning NULL]");
    }
    return NULL;
  }

  Klass* klass = java_lang_Class::as_Klass(mirror);
  assert(klass->oop_is_instance() ||
    klass->oop_is_objArray() ||
    klass->oop_is_typeArray(),
    "Bad Klass");
  oop module;
  if (klass->oop_is_instance()) {
    module = java_lang_Class::module(mirror);
  }
  else if (klass->oop_is_objArray()) {
    ObjArrayKlass* obj_arr_klass = ObjArrayKlass::cast(klass);
    Klass* bottom_klass = obj_arr_klass->bottom_klass();
    module = java_lang_Class::module(bottom_klass->java_mirror());
  }
  else if (klass->oop_is_typeArray()) {
    return NULL;  // Primitive type
  }

  if (TraceModules) {
    ResourceMark rm;
    if (module != NULL) {
      oop module_name = java_lang_reflect_Module::name(module);
      tty->print("[JVM_GetModule: module ");
      java_lang_String::print(module_name, tty);
    }
    else {
      tty->print("[JVM_GetModule: unamed module");
    }
    tty->print_cr(" for class %s]", klass->external_name());
  }

  return JNIHandles::make_local(env, module);

}

void modules::add_module_package(JNIEnv *env, jobject module, jstring package) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);
  ResourceMark rm;

  if (module == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "module is null");
  }
  ModuleEntry* module_entry = get_module_entry(module, CHECK);
  if (module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      "module is invalid");
  }
  char *package_name = java_lang_String::as_utf8_string(
    JNIHandles::resolve_non_null(package));
  if (package_name == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(), "Bad package");
  }
  if (!verify_package_name(package_name)) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
      err_msg("Invalid package name: %s", package_name));
  }

  if (TraceModules) {
    ResourceMark rm;
    tty->print_cr("[JVM_AddModulePackage: Adding package %s to module %s]",
      package_name, module_entry->name()->as_C_string());
  }

  Symbol *pkg_symbol = SymbolTable::new_symbol(package_name, CHECK);
  PackageEntryTable* package_table = module_entry->loader()->packages();
  assert(package_table != NULL, "Missing package_table");

  {
    MutexLocker ml(Module_lock, THREAD);

    // Check that the package does not exist in the class loader's package table.
    if (package_table->lookup_only(pkg_symbol)) {
      THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
        err_msg("Package %s already exists for class loader", package_name));
    }

    package_table->locked_create_entry(pkg_symbol, module_entry, CHECK);
  }
}
