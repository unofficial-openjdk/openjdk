/*
* Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/symbolTable.hpp"
#include "classfile/vmSymbols.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/objArrayKlass.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "runtime/arguments.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/reflection.hpp"
#include "utilities/utf8.hpp"


static bool verify_module_name(char *module_name) {
  if (module_name == NULL) return false;
  int len = (int)strlen(module_name);
  return (len > 0 && len <= Symbol::max_length() &&
    UTF8::is_legal_utf8((unsigned char *)module_name, len, false) &&
    ClassFileParser::verify_unqualified_name(module_name, len,
    ClassFileParser::LegalModule));
}

bool Modules::verify_package_name(char *package_name) {
  if (package_name == NULL) return false;
  int len = (int)strlen(package_name);
  return (len > 0 && len <= Symbol::max_length() &&
    UTF8::is_legal_utf8((unsigned char *)package_name, len, false) &&
    ClassFileParser::verify_unqualified_name(package_name, len,
    ClassFileParser::LegalClass));
}

static ModuleEntryTable* get_module_entry_table(Handle h_loader, TRAPS) {
  // This code can be called during start-up, before the classLoader's classLoader data got
  // created.  So, call register_loader() to make sure the classLoader data gets created.
  ClassLoaderData *loader_cld = SystemDictionary::register_loader(h_loader, CHECK_NULL);
  return loader_cld->modules();
}

static PackageEntryTable* get_package_entry_table(Handle h_loader, TRAPS) {
  // This code can be called during start-up, before the classLoader's classLoader data got
  // created.  So, call register_loader() to make sure the classLoader data gets created.
  ClassLoaderData *loader_cld = SystemDictionary::register_loader(h_loader, CHECK_NULL);
  return loader_cld->packages();
}

static PackageEntry* get_package_entry_by_name(Symbol* package,
                                               Handle h_loader,
                                               TRAPS) {
  if (package != NULL) {
    ResourceMark rm;
    if (Modules::verify_package_name(package->as_C_string())) {
      PackageEntryTable* const package_entry_table =
        get_package_entry_table(h_loader, THREAD);
      assert(package_entry_table != NULL, "Unexpected null package entry table");
      return package_entry_table->lookup_only(package);
    }
  }
  return NULL;
}

static ModuleEntry* get_module_entry_by_package_name(Symbol* package,
                                                     Handle h_loader,
                                                     TRAPS) {
  const PackageEntry* const pkg_entry =
    get_package_entry_by_name(package, h_loader, THREAD);

  return pkg_entry != NULL ? pkg_entry->module() : NULL;
}

// Check if -Xoverride:<path> was specified.  If so, prepend <path>/module_name,
// if it exists, to bootpath so boot loader can find the class files.  Also, if
// using exploded modules, prepend <java.home>/modules/module_name, if it exists,
// to bootpath so that its class files can be found by the boot loader.
static void add_to_boot_loader_list(char *module_name, TRAPS) {
  // java.base should be handled by argument parsing.
  assert(strcmp(module_name, "java.base") != 0, "Unexpected java.base module name");
  char file_sep = os::file_separator()[0];
  size_t module_len = strlen(module_name);

  // If -Xoverride is set then add path <override-dir>/module_name.
  char* prefix_path = NULL;
  if (Arguments::override_dir() != NULL) {
    size_t len = strlen(Arguments::override_dir()) + module_len + 2;
    prefix_path = NEW_C_HEAP_ARRAY(char, len, mtInternal);
    jio_snprintf(prefix_path, len, "%s%c%s", Arguments::override_dir(), file_sep, module_name);
    struct stat st;
    // See if Xoverride module path exists.
    if ((os::stat(prefix_path, &st) != 0)) {
      FREE_C_HEAP_ARRAY(char, prefix_path);
      prefix_path = NULL;
    }
  }

  // If bootmodules.jimage does not exist then assume exploded form
  // ${java.home}/modules/<module-name>
  char* path = NULL;
  if (!ClassLoader::has_bootmodules_jimage()) {
    const char* home = Arguments::get_java_home();
    size_t len = strlen(home) + module_len + 32;
    path = NEW_C_HEAP_ARRAY(char, len, mtInternal);
    jio_snprintf(path, len, "%s%cmodules%c%s", home, file_sep, file_sep, module_name);
    struct stat st;
    // See if exploded module path exists.
    if ((os::stat(path, &st) != 0)) {
      FREE_C_HEAP_ARRAY(char, path);
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
  assert(module_table != NULL, "Unexpected null module entry table");
  return module_table->lookup_only(h_module());
}

static PackageEntry* get_package_entry(ModuleEntry* module_entry, jstring package, TRAPS) {
  ResourceMark rm;
  if (package == NULL) return NULL;
  const char *package_name = java_lang_String::as_utf8_string(JNIHandles::resolve_non_null(package));
  if (package_name == NULL) return NULL;
  TempNewSymbol pkg_symbol = SymbolTable::new_symbol(package_name, CHECK_NULL);
  PackageEntryTable* package_entry_table = module_entry->loader()->packages();
  assert(package_entry_table != NULL, "Unexpected null package entry table");
  return package_entry_table->lookup_only(pkg_symbol);
}


static const char* get_module_version(jstring version) {
  char* module_version;
  if (version == NULL) {
    module_version = (char *)Modules::default_version();
  } else {
    module_version = java_lang_String::as_utf8_string(JNIHandles::resolve_non_null(version));
    if (module_version == NULL) module_version = (char *)Modules::default_version();
  }
  return module_version;
}

static char* get_module_name(oop module, TRAPS) {
  oop name_oop = java_lang_reflect_Module::name(module);
  if (name_oop == NULL) {
    THROW_MSG_NULL(vmSymbols::java_lang_NullPointerException(), "Null module name");
  }
  char* module_name = java_lang_String::as_utf8_string(name_oop);
  if (!verify_module_name(module_name)) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
                   err_msg("Invalid module name: %s",
                           module_name != NULL ? module_name : "NULL"));
  }
  if (strcmp(module_name, vmSymbols::java_base()->as_C_string()) == 0) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
                   "Module java.base is already defined");
  }
  return module_name;
}


void Modules::define_module(JNIEnv *env, jobject module, jstring version,
                            jstring location, jobjectArray packages) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);
  ResourceMark rm(THREAD);

  if (module == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(), "Null module object");
  }
  Handle jlrM_handle(THREAD, JNIHandles::resolve(module));

  char* module_name = get_module_name(jlrM_handle(), CHECK);
  const char* module_version = get_module_version(version);

  if (TraceModules) {
    tty->print_cr("In define_module(): Start defining module %s, version: %s]",
                  module_name, module_version);
  }

  objArrayOop packages_oop = objArrayOop(JNIHandles::resolve(packages));
  objArrayHandle packages_h(THREAD, packages_oop);
  int num_packages = (packages_h == NULL ? 0 : packages_h->length());

  // Check that the list of packages has no duplicates and that the
  // packages are syntactically ok.
  GrowableArray<Symbol*>* pkg_list = new GrowableArray<Symbol*>(num_packages);
  for (int x = 0; x < num_packages; x++) {
    oop string_obj = packages_h->obj_at(x);

    if (string_obj == NULL || !string_obj->is_a(SystemDictionary::String_klass())) {
      THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
                err_msg("Bad package name for module: %s", module_name));
    }
    char *package_name = java_lang_String::as_utf8_string(string_obj);
    if (!verify_package_name(package_name)) {
      THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
                 err_msg("Invalid package name: %s for module: %s",
                         package_name, module_name));
    }
    Symbol* pkg_symbol = SymbolTable::new_symbol(package_name, CHECK);
    // append_if_missing() returns FALSE if entry already exists.
    if (!pkg_list->append_if_missing(pkg_symbol)) {
      THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
                err_msg("Duplicate package name: %s for module %s",
                        package_name, module_name));
    }
  }

  oop loader = java_lang_reflect_Module::loader(jlrM_handle());
  // Make sure loader is not the sun.reflect.DelegatingClassLoader.
  if (loader != java_lang_ClassLoader::non_reflection_class_loader(loader)) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              "Class loader is an invalid delegating class loader");
  }
  Handle h_loader = Handle(THREAD, loader);

  // Check that loader is a subclass of java.lang.ClassLoader.
  if (loader != NULL && !java_lang_ClassLoader::is_subclass(h_loader->klass())) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              "Class loader is not a subclass of java.lang.ClassLoader");
  }

  ModuleEntryTable* module_table = get_module_entry_table(h_loader, CHECK);
  assert(module_table != NULL, "module entry table shouldn't be null");

  // Create symbol* entry for module name.
  TempNewSymbol module_symbol = SymbolTable::new_symbol(module_name, CHECK);

  int dupl_pkg_index = -1;
  bool dupl_modules = false;
  {
    MutexLocker ml(Module_lock, THREAD);

    PackageEntryTable* package_table = NULL;
    if (num_packages > 0) {
      package_table = get_package_entry_table(h_loader, CHECK);
      assert(package_table != NULL, "Missing package_table");

      // Check that none of the packages exist in the class loader's package table.
      for (int x = 0; x < pkg_list->length(); x++) {
        if (package_table->lookup_only(pkg_list->at(x))) {
          // This could be because the module was already defined.  If so,
          // report that error instead of the package error.
          if (module_table->lookup_only(module_symbol) != NULL) {
            dupl_modules = true;
          } else {
            dupl_pkg_index = x;
          }
          break;
        }
      }
    }  // if (num_packages > 0)...

    // Add the module and its packages.
    if (!dupl_modules && dupl_pkg_index == -1) {
      // Create the entry for this module in the class loader's module entry table.

      // Create symbol* entry for module version.
      TempNewSymbol version_symbol = SymbolTable::new_symbol(module_version, CHECK);

      // Create symbol* entry for module location.
      const char* module_location = NULL;
      TempNewSymbol location_symbol = NULL;
      if (location != NULL) {
        module_location =
          java_lang_String::as_utf8_string(JNIHandles::resolve_non_null(location));
        if (module_location != NULL) {
          location_symbol = SymbolTable::new_symbol(module_location, CHECK);
        }
      }

      ClassLoaderData* loader_data =
        ClassLoaderData::class_loader_data_or_null(h_loader());
      assert(loader_data != NULL, "class loader data shouldn't be null");
      ModuleEntry* module_entry =
        module_table->locked_create_entry_or_null(jlrM_handle(), module_symbol,
          version_symbol, location_symbol, loader_data);

      if (module_entry == NULL) {
        dupl_modules = true;
      } else {
        if (TraceModules) {
          tty->print("In define_module(): creation of module: %s, version: %s, location: %s, ",
            module_name, module_version, module_location != NULL ? module_location : "NULL");
          loader_data->print_value();
          tty->print_cr(", package #: %d]", pkg_list->length());
        }

        // Add the packages.
        assert(pkg_list->length() == 0 || package_table != NULL, "Bad package table");
        PackageEntry* pkg;
        for (int y = 0; y < pkg_list->length(); y++) {
          pkg = package_table->locked_create_entry_or_null(pkg_list->at(y), module_entry);
          assert(pkg != NULL, "Unable to create a module's package entry");

          if (TraceModules || TracePackages) {
            tty->print_cr("[In define_module(): creation of package %s for module %s]",
                          (pkg_list->at(y))->as_C_string(), module_name);
          }

          // Unable to have a GrowableArray of TempNewSymbol.  Must decrement the refcount of
          // the Symbol* that was created above for each package. The refcount was incremented
          // by SymbolTable::new_symbol and as well by the PackageEntry creation.
          pkg_list->at(y)->decrement_refcount();
        }
      }
    }
  }  // Release the lock

  // any errors ?
  if (dupl_modules) {
     THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
               err_msg("Module %s is already defined", module_name));
  }
  if (dupl_pkg_index != -1) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              err_msg("Package %s for module %s already exists for class loader",
                       pkg_list->at(dupl_pkg_index)->as_C_string(), module_name));
  }

  if (loader == NULL && !Universe::is_module_initialized()) {
    // Now that the module is defined, if it is in the bootloader, make sure that
    // its classes can be found.  Check if -Xoverride:<path> was specified.  If
    // so prepend <path>/module_name, if it exists, to bootpath.  Also, if using
    // exploded modules, prepend <java.home>/modules/module_name, if it exists,
    // to bootpath.
    add_to_boot_loader_list(module_name, CHECK);
  }
}

void Modules::add_module_exports(JNIEnv *env, jobject from_module, jstring package, jobject to_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (package == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(),
              "package is null");
  }
  if (from_module == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(),
              "from_module is null");
  }
  ModuleEntry* from_module_entry = get_module_entry(from_module, CHECK);
  if (from_module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              "from_module cannot be found");
  }
  ModuleEntry* to_module_entry;
  if (to_module == NULL) {
    to_module_entry = NULL;  // It's the unnamed module.
  }
  else {
    to_module_entry = get_module_entry(to_module, CHECK);
    if (to_module_entry == NULL) {
      THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
                "to_module is invalid");
    }
  }

  PackageEntry *package_entry = get_package_entry(from_module_entry, package, CHECK);

  if (package_entry == NULL) {
    ResourceMark rm;
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              err_msg("Package %s not found in from_module %s",
                      java_lang_String::as_utf8_string(JNIHandles::resolve_non_null(package)),
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
    tty->print_cr("[add_module_exports(): package:module %s:%s is exported to module %s]",
                  package_entry->name()->as_C_string(),
                  from_module_entry->name()->as_C_string(),
                  ((to_module_entry == NULL) ? NULL : to_module_entry->name()->as_C_string()));
  }

  // If this is a qualified export, make sure the entry has not already been exported
  // unqualifiedly.
  if (to_module_entry != NULL && package_entry->is_unqual_exported()) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              err_msg("Bad qualifed export, package %s in module %s is already unqualifiedly exported",
                      package_entry->name()->as_C_string(),
                      from_module_entry->name()->as_C_string()));
  }

  // Do nothing if modules are the same.
  if (from_module_entry != to_module_entry) {
    package_entry->set_exported(to_module_entry);
  }
}

void Modules::add_reads_module(JNIEnv *env, jobject from_module, jobject to_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (from_module == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(),
              "from_module is null");
  }
  if (to_module == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(),
              "to_module is null");
  }

  ModuleEntry* from_module_entry = get_module_entry(from_module, CHECK);
  if (from_module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              "from_module is not valid");
  }
  ModuleEntry* to_module_entry = get_module_entry(to_module, CHECK);
  if (to_module_entry == NULL) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              "to_module is invalid");
  }

  if (TraceModules) {
    ResourceMark rm;
    tty->print_cr("[add_reads_module(): Adding read from module %s to module %s]",
      from_module_entry->name()->as_C_string(),
      to_module_entry->name()->as_C_string());
  }

  // if modules are the same, no need to add the read.
  if (from_module_entry != to_module_entry) {
    from_module_entry->add_read(to_module_entry);
  }
}

jboolean Modules::can_read_module(JNIEnv *env, jobject asking_module, jobject target_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (asking_module == NULL) {
    THROW_MSG_(vmSymbols::java_lang_NullPointerException(),
               "asking_module is null", JNI_FALSE);
  }

  ModuleEntry* asking_module_entry = get_module_entry(asking_module, CHECK_false);
  if (asking_module_entry == NULL) {
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
               "asking_module is invalid", JNI_FALSE);
  }

  if (target_module == NULL) {
    return JNI_TRUE;  // Unnamed module is always readable.
  }

  ModuleEntry* target_module_entry = get_module_entry(target_module, CHECK_false);
  if (target_module_entry == NULL) {
    THROW_MSG_(vmSymbols::java_lang_IllegalArgumentException(),
               "target_module is invalid", JNI_FALSE);
  }

  if (TraceModules) {
    ResourceMark rm;
    tty->print_cr("[can_read_module(): module %s trying to read module %s, allowed = %s",
                  asking_module_entry->name()->as_C_string(),
                  target_module_entry->name()->as_C_string(),
                  BOOL_TO_STR((asking_module_entry == target_module_entry) ||
                    (asking_module_entry->can_read(target_module_entry))));
  }

  if (asking_module_entry == target_module_entry) {
    return true;
  }
  return asking_module_entry->can_read(target_module_entry);
}

jboolean Modules::is_exported_to_module(JNIEnv *env, jobject from_module, jstring package, jobject to_module) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);

  if (package == NULL) {
    THROW_MSG_(vmSymbols::java_lang_NullPointerException(),
               "package is null", JNI_FALSE);
  }
  if (from_module == NULL) {
    THROW_MSG_(vmSymbols::java_lang_NullPointerException(),
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
    if (to_module_entry == NULL) {
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
    tty->print_cr("[is_exported_to_module: package %s from module %s checking if exported to module %s, exported? = %s",
                  package_entry->name()->as_C_string(),
                  from_module_entry->name()->as_C_string(),
                  to_module_entry != NULL ? to_module_entry->name()->as_C_string() : "unnamed",
                  BOOL_TO_STR(package_entry->is_unqual_exported() ||
                    (to_module != NULL && package_entry->is_qexported_to(to_module_entry)) ||
                    (from_module_entry == to_module_entry)));
  }

  return (package_entry->is_unqual_exported() ||
          (from_module_entry == to_module_entry) ||
          (to_module != NULL && package_entry->is_qexported_to(to_module_entry)));
}

jobject Modules::get_module(JNIEnv *env, jclass clazz) {
  oop mirror = JNIHandles::resolve_non_null(clazz);
  if (mirror == NULL || java_lang_Class::is_primitive(mirror)) {
    if (TraceModules) {
      tty->print_cr("[get_module(): returning NULL]");
    }
    return NULL;
  }

  Klass* klass = java_lang_Class::as_Klass(mirror);
  assert(klass->oop_is_instance() || klass->oop_is_objArray() ||
    klass->oop_is_typeArray(), "Bad Klass");

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
    Klass* obj_k = SystemDictionary::Object_klass();
    module = java_lang_Class::module(obj_k->java_mirror());
  }

  if (TraceModules) {
    ResourceMark rm;
    if (module != NULL) {
      oop module_name = java_lang_reflect_Module::name(module);
      tty->print("[get_module(): module ");
      java_lang_String::print(module_name, tty);
    }
    else {
      tty->print("[get_module(): unamed module");
    }
    tty->print_cr(" for class %s]", klass->external_name());
  }

  return JNIHandles::make_local(env, module);
}

jobject Modules::get_module(Symbol* package_name,
                           Handle h_loader,
                           TRAPS) {

  const ModuleEntry* const module =
    get_module_entry_by_package_name(package_name,
                                     h_loader,
                                     THREAD);

  return module != NULL ?
    JNIHandles::make_local(THREAD, module->literal()) : NULL;
}

void Modules::add_module_package(JNIEnv *env, jobject module, jstring package) {
  JavaThread *THREAD = JavaThread::thread_from_jni_environment(env);
  ResourceMark rm;

  if (module == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(),
              "module is null");
  }
  if (package == NULL) {
    THROW_MSG(vmSymbols::java_lang_NullPointerException(),
              "package is null");
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
    tty->print_cr("[add_module_package(): Adding package %s to module %s]",
                  package_name, module_entry->name()->as_C_string());
  }

  TempNewSymbol pkg_symbol = SymbolTable::new_symbol(package_name, CHECK);
  PackageEntryTable* package_table = module_entry->loader()->packages();
  assert(package_table != NULL, "Missing package_table");

  bool pkg_exists = false;
  {
    MutexLocker ml(Module_lock, THREAD);

    // Check that the package does not exist in the class loader's package table.
    if (!package_table->lookup_only(pkg_symbol)) {
      PackageEntry* pkg = package_table->locked_create_entry_or_null(pkg_symbol, module_entry);
      assert(pkg != NULL, "Unable to create a module's package entry");
    } else {
      pkg_exists = true;
    }
  }
  if (pkg_exists) {
    THROW_MSG(vmSymbols::java_lang_IllegalArgumentException(),
              err_msg("Package %s already exists for class loader", package_name));
  }
}

bool Modules::is_package_defined(Symbol* package, Handle h_loader, TRAPS) {
  return get_package_entry_by_name(package, h_loader, THREAD) != NULL;
}
