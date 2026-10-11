# libdroidnative.so: Wayland compositor + JNI glue.
# Needs the static libwayland-server/libffi and the host wayland-scanner from
# native-host/build.sh (in <repo>/.cache/native-build).
cmake_minimum_required(VERSION 3.22)
project(droidnative C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_EXTENSIONS ON)

set(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
set(NATIVE_BUILD ${REPO}/.cache/native-build)
set(PREFIX ${NATIVE_BUILD}/${ANDROID_ABI}/prefix)
set(WAYLAND_SCANNER ${NATIVE_BUILD}/host/bin/wayland-scanner)
if(NOT EXISTS ${PREFIX}/lib/libwayland-server.a OR NOT EXISTS ${WAYLAND_SCANNER})
    message(FATAL_ERROR "Run native-host/build.sh first (missing ${PREFIX})")
endif()

set(PROTO ${CMAKE_CURRENT_SOURCE_DIR}/protocol)
set(GEN ${CMAKE_CURRENT_BINARY_DIR}/gen)
file(MAKE_DIRECTORY ${GEN})
set(GEN_SRC "")
foreach(p xdg-shell linux-dmabuf-unstable-v1 xdg-decoration-unstable-v1
          text-input-unstable-v3 xwayland-shell-v1)
    add_custom_command(
        OUTPUT ${GEN}/${p}-server-protocol.h ${GEN}/${p}-protocol.c
        COMMAND ${WAYLAND_SCANNER} server-header ${PROTO}/${p}.xml ${GEN}/${p}-server-protocol.h
        COMMAND ${WAYLAND_SCANNER} private-code ${PROTO}/${p}.xml ${GEN}/${p}-protocol.c
        DEPENDS ${PROTO}/${p}.xml)
    list(APPEND GEN_SRC ${GEN}/${p}-server-protocol.h ${GEN}/${p}-protocol.c)
endforeach()

# Private protocol of dn-xwm (guest X window manager), kept with its package
set(DN_XWM_XML ${CMAKE_CURRENT_SOURCE_DIR}/../../../../linux-side/srcpkgs/droidnative-tools/files/dn-xwm-v1.xml)
add_custom_command(
    OUTPUT ${GEN}/dn-xwm-v1-server-protocol.h ${GEN}/dn-xwm-v1-protocol.c
    COMMAND ${WAYLAND_SCANNER} server-header ${DN_XWM_XML} ${GEN}/dn-xwm-v1-server-protocol.h
    COMMAND ${WAYLAND_SCANNER} private-code ${DN_XWM_XML} ${GEN}/dn-xwm-v1-protocol.c
    DEPENDS ${DN_XWM_XML})
list(APPEND GEN_SRC ${GEN}/dn-xwm-v1-server-protocol.h ${GEN}/dn-xwm-v1-protocol.c)

add_library(wayland-server STATIC IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION ${PREFIX}/lib/libwayland-server.a)
add_library(ffi STATIC IMPORTED)
set_target_properties(ffi PROPERTIES IMPORTED_LOCATION ${PREFIX}/lib/libffi.a)

add_library(droidnative SHARED
    compositor/server.c
    compositor/surface.c
    compositor/xdg.c
    compositor/dmabuf.c
    compositor/seat.c
    compositor/output.c
    compositor/data_device.c
    compositor/decoration.c
    compositor/text_input.c
    compositor/xwayland.c
    compositor/render_gles.c
    compositor/render_null.c
    jni/jni_compositor.c
    ${GEN_SRC})
target_include_directories(droidnative PRIVATE compositor ${GEN} ${PREFIX}/include)
target_compile_options(droidnative PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
target_link_libraries(droidnative PRIVATE wayland-server ffi EGL GLESv3 android log)
target_link_options(droidnative PRIVATE -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
