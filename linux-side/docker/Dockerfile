# Build environment for the Linux-side packages (xbps-src cross builds).
# Used by linux-side/build-packages.sh; needs to run with --privileged
# because xbps-src uses the uchroot chroot style, as Void's own CI does.
FROM ghcr.io/void-linux/void-glibc-full:latest

ARG VOID_PACKAGES_COMMIT=8627c5f26df532d791caf17f79115ecf02db1eaa

RUN mkdir -p /etc/xbps.d \
 && cp /usr/share/xbps.d/*-repository-*.conf /etc/xbps.d/ \
 && xbps-install -Syu xbps \
 && xbps-install -yu \
 && xbps-install -y bash curl git sudo \
 && useradd -G xbuilder -m builder

USER builder
RUN git init -q /home/builder/void-packages \
 && cd /home/builder/void-packages \
 && git remote add origin https://github.com/void-linux/void-packages.git \
 && git fetch -q --depth 1 origin "$VOID_PACKAGES_COMMIT" \
 && git checkout -q FETCH_HEAD \
 && mkdir masterdir hostdir \
 && printf '%s\n' XBPS_CHROOT_CMD=uchroot XBPS_ALLOW_RESTRICTED=no \
      'XBPS_MAKEJOBS=$(nproc)' > etc/conf

WORKDIR /home/builder/void-packages
COPY --chown=builder:builder entrypoint.sh /home/builder/entrypoint.sh
ENTRYPOINT ["/home/builder/entrypoint.sh"]
