cmake_minimum_required(VERSION 3.22.1)
project(s25u_native LANGUAGES C ASM)

add_library(s25u_native SHARED native_probe.c)
target_compile_options(s25u_native PRIVATE -O2 -Wall -Wextra -Werror)
target_link_libraries(s25u_native PRIVATE log)

# DirtyFrag exploit (diabl0w/DFRoot) - see dirtyfrag/DF_ORIGIN.md.
# A second native library; the M3Q flow (s25u_native) is untouched.
set(DF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/dirtyfrag)
set(DF_SPLICEHELPER ${CMAKE_CURRENT_BINARY_DIR}/splicehelper)
add_custom_command(
    OUTPUT ${DF_SPLICEHELPER}
    COMMAND "${CMAKE_C_COMPILER}" --target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT}
            "${DF_DIR}/splicehelper.c" -o "${DF_SPLICEHELPER}"
            -nodefaultlibs -nostartfiles -ffreestanding -static
    COMMAND "${CMAKE_STRIP}" "${DF_SPLICEHELPER}"
    DEPENDS "${DF_DIR}/splicehelper.c"
    COMMENT "Building DirtyFrag splicehelper")
add_custom_target(df_splicehelper_bin DEPENDS "${DF_SPLICEHELPER}")
add_library(exp SHARED ${DF_DIR}/exp.c ${DF_DIR}/libcxx.S ${DF_DIR}/elf_parser.c)
add_dependencies(exp df_splicehelper_bin)
target_include_directories(exp PRIVATE ${DF_DIR} ${CMAKE_CURRENT_BINARY_DIR})
target_compile_options(exp PRIVATE -O2 -Wall -Wextra -fno-stack-protector -fomit-frame-pointer)
target_link_libraries(exp PRIVATE log)
target_link_options(exp PRIVATE "-Wl,-z,max-page-size=16384")
set_source_files_properties(${DF_DIR}/exp.c PROPERTIES OBJECT_DEPENDS
    "${DF_DIR}/ko/dirtyfrag-android12-5.10.ko;${DF_DIR}/ko/dirtyfrag-android13-5.10.ko;${DF_DIR}/ko/dirtyfrag-android13-5.15.ko;${DF_DIR}/ko/dirtyfrag-android14-5.15.ko;${DF_DIR}/ko/dirtyfrag-android14-6.1.ko;${DF_DIR}/ko/dirtyfrag-android15-6.6.ko;${DF_DIR}/ko/dirtyfrag-android16-6.12.ko;${DF_DIR}/ko/dirtyfrag-android16-6.12-kernelsu.ko;${DF_DIR}/ko/dirtyfrag-android16-6.12-kernelsu-next.ko;${DF_DIR}/ko/dirtyfrag-android16-6.12-resukisu.ko;${DF_DIR}/ko/dirtyfrag-android17-6.18.ko;${DF_SPLICEHELPER}")

