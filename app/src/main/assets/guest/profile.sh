# /etc/profile.d/droidnative.sh, installed by DroidNative
export XDG_RUNTIME_DIR=/run/droidnative
export WAYLAND_DISPLAY=wayland-0
export DISPLAY=:0
export XDG_SESSION_TYPE=wayland
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM=wayland
export SDL_VIDEODRIVER=wayland
# Turnip: expose Vulkan 1.3 on Adreno without hardware multiview (a610, a702)
if [ -e /dev/kgsl-3d0 ]; then
    export TU_DEBUG="${TU_DEBUG:-noconform}"
fi
