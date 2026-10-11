# Host (Linux desktop) build of the compositor core with the null renderer,
# plus the dn-wltest client. Driven by tests/host/compositor/run.sh.
cmake_minimum_required(VERSION 3.16)
project(dnc_host_tests C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_EXTENSIONS ON)
add_compile_options(-Wall -Wextra -Werror=implicit-function-declaration
                    -fsanitize=address,undefined -g)
add_link_options(-fsanitize=address,undefined)

set(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../../..)
set(CPP ${REPO}/app/src/main/cpp)
set(PROTO ${CPP}/protocol)
find_program(WAYLAND_SCANNER wayland-scanner
             HINTS ${REPO}/.cache/native-build/host/bin REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(WL REQUIRED wayland-server wayland-client)

set(GEN ${CMAKE_CURRENT_BINARY_DIR}/gen)
file(MAKE_DIRECTORY ${GEN})
set(GEN_SERVER "")
set(GEN_CLIENT "")
foreach(p xdg-shell linux-dmabuf-unstable-v1 xdg-decoration-unstable-v1
          text-input-unstable-v3 xwayland-shell-v1)
    add_custom_command(
        OUTPUT ${GEN}/${p}-server-protocol.h ${GEN}/${p}-client-protocol.h ${GEN}/${p}-protocol.c
        COMMAND ${WAYLAND_SCANNER} server-header ${PROTO}/${p}.xml ${GEN}/${p}-server-protocol.h
        COMMAND ${WAYLAND_SCANNER} client-header ${PROTO}/${p}.xml ${GEN}/${p}-client-protocol.h
        COMMAND ${WAYLAND_SCANNER} private-code ${PROTO}/${p}.xml ${GEN}/${p}-protocol.c
        DEPENDS ${PROTO}/${p}.xml)
    list(APPEND GEN_SERVER ${GEN}/${p}-server-protocol.h ${GEN}/${p}-protocol.c)
    list(APPEND GEN_CLIENT ${GEN}/${p}-client-protocol.h ${GEN}/${p}-protocol.c)
endforeach()

set(DN_XWM_XML ${REPO}/linux-side/srcpkgs/droidnative-tools/files/dn-xwm-v1.xml)
add_custom_command(
    OUTPUT ${GEN}/dn-xwm-v1-server-protocol.h ${GEN}/dn-xwm-v1-client-protocol.h ${GEN}/dn-xwm-v1-protocol.c
    COMMAND ${WAYLAND_SCANNER} server-header ${DN_XWM_XML} ${GEN}/dn-xwm-v1-server-protocol.h
    COMMAND ${WAYLAND_SCANNER} client-header ${DN_XWM_XML} ${GEN}/dn-xwm-v1-client-protocol.h
    COMMAND ${WAYLAND_SCANNER} private-code ${DN_XWM_XML} ${GEN}/dn-xwm-v1-protocol.c
    DEPENDS ${DN_XWM_XML})
list(APPEND GEN_SERVER ${GEN}/dn-xwm-v1-server-protocol.h ${GEN}/dn-xwm-v1-protocol.c)

add_executable(dnc-headless
    headless.c
    ${CPP}/compositor/server.c
    ${CPP}/compositor/surface.c
    ${CPP}/compositor/xdg.c
    ${CPP}/compositor/dmabuf.c
    ${CPP}/compositor/seat.c
    ${CPP}/compositor/output.c
    ${CPP}/compositor/data_device.c
    ${CPP}/compositor/decoration.c
    ${CPP}/compositor/text_input.c
    ${CPP}/compositor/xwayland.c
    ${CPP}/compositor/render_null.c
    ${GEN_SERVER})
target_include_directories(dnc-headless PRIVATE ${CPP}/compositor ${GEN} ${WL_INCLUDE_DIRS})
target_link_libraries(dnc-headless PRIVATE ${WL_LINK_LIBRARIES} pthread)

add_executable(dn-wltest ${REPO}/linux-side/srcpkgs/droidnative-tools/files/dn-wltest.c ${GEN_CLIENT})
target_include_directories(dn-wltest PRIVATE ${GEN} ${WL_INCLUDE_DIRS})
target_link_libraries(dn-wltest PRIVATE ${WL_LINK_LIBRARIES})
