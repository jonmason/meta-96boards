require edk2_git.bb

COMPATIBLE_MACHINE = "hikey"

DEPENDS:append = " dosfstools-native gptfdisk-native mtools-native virtual/fakeroot-native grub-efi optee-os"

inherit deploy python3native

SRCREV_edk2 = "77326b5a153513c826d5a50363eace6ef6b59413"
SRCREV_atf = "ed8112606c54d85781fc8429160883d6310ece32"
SRCREV_openplatformpkg = "328df4c4aa0146c9b5ba7510bd342fc7afd9dd59"
SRCREV_uefitools = "b37391801290b4adbbc821832470216e98d4e900"
SRCREV_lloader = "c1cbbf8ab824820b5c1769a1c80dd234c5b57ffc"
SRCREV_atffastboot = "af5ddb16266e54745d3b2e354d32b54fefbbbd78"

### DISCLAIMER ###
# l-loader should be built with an aarch32 toolchain but we target an
# ARMv8 machine. OE cross-toolchain is aarch64 in this case.
# We decided to use an external pre-built toolchain in order to build
# l-loader.
# knowledgeably, it is a hack...
###
SRC_URI = "git://github.com/96boards-hikey/edk2.git;name=edk2;branch=testing/hikey960_v2.5 \
           git://github.com/ARM-software/arm-trusted-firmware.git;name=atf;branch=master;destsuffix=git/atf \
           git://github.com/96boards-hikey/OpenPlatformPkg.git;name=openplatformpkg;branch=testing/hikey960_v1.3.4;destsuffix=git/OpenPlatformPkg \
           git://git.linaro.org/uefi/uefi-tools.git;name=uefitools;destsuffix=git/uefi-tools \
           git://github.com/96boards-hikey/l-loader.git;name=lloader;branch=testing/hikey960_v1.2;destsuffix=git/l-loader \
           git://github.com/96boards-hikey/atf-fastboot.git;name=atffastboot;destsuffix=git/atf-fastboot \
           http://releases.linaro.org/components/toolchain/binaries/6.4-2017.08/arm-linux-gnueabihf/gcc-linaro-6.4.1-2017.08-x86_64_arm-linux-gnueabihf.tar.xz;name=tc \
           file://grub.cfg.in \
          "

SRC_URI[tc.md5sum] = "8c6084924df023d1e5c0bac2a4ccfa2f"
SRC_URI[tc.sha256sum] = "1c975a1936cc966099b3fcaff8f387d748caff27f43593214ae6d4601241ae40"

# /usr/lib/edk2/bl1.bin not shipped files. [installed-vs-shipped]
INSANE_SKIP:${PN} += "installed-vs-shipped"

OPTEE_OS_ARG = "-s ${EDK2_DIR}/optee_os"
REMOVE_MACRO_PREFIX_MAP = "-fmacro-prefix-map=${WORKDIR}=/usr/src/debug/${PN}/${EXTENDPE}${PV}-${PR}"
DEBUG_PREFIX_MAP:remove = "${@'${REMOVE_MACRO_PREFIX_MAP}'"

# workaround EDK2 is confused by the long path used during the build
# and truncate files name expected by VfrCompile
do_patch[postfuncs] += "set_max_path"
set_max_path () {
    sed -i -e 's/^#define MAX_PATH.*/#define MAX_PATH 511/' ${S}/BaseTools/Source/C/VfrCompile/EfiVfr.h
}

do_compile:prepend() {
    unset LDFLAGS
    unset CFLAGS
    unset CPPFLAGS
    # Fix hardcoded value introduced in
    # https://git.linaro.org/uefi/uefi-tools.git/commit/common-functions?id=65e8e8df04f34fc2a87ae9d34f5ef5b6fee5a396
    sed -i -e 's/aarch64-linux-gnu-/${TARGET_PREFIX}/' ${S}/uefi-tools/common-functions

    # We need the secure payload (Trusted OS) built from OP-TEE Trusted OS (tee-pager.bin)
    # but we have already built tee-pager.bin from optee-os recipe
    # Copy tee-pager.bin and create dummy files to make uefi-build.sh script happy
    install -D -p -m0644 \
      ${STAGING_DIR_HOST}${nonarch_base_libdir}/firmware/tee-pager.bin \
      ${EDK2_DIR}/optee_os/out/arm-plat-hikey/core/tee-pager.bin

    # opteed-build.sh script has a few assumptions...
    mkdir -p ${EDK2_DIR}/optee_os/documentation
    touch ${EDK2_DIR}/optee_os/documentation/optee_design.md

    printf "all:\n"  > ${EDK2_DIR}/optee_os/Makefile
    printf "\ttrue" >> ${EDK2_DIR}/optee_os/Makefile

    # Disable fakeroot
    sed -i -e 's:fakeroot ::g' ${S}/l-loader/generate_ptable.sh
}

fakeroot do_compile:append() {
    # Use pre-built aarch32 toolchain
    export PATH=${WORKDIR}/gcc-linaro-6.4.1-2017.08-x86_64_arm-linux-gnueabihf/bin:$PATH

    # HiKey requires an ATF fork for the recovery mode
    cd ${EDK2_DIR}/atf-fastboot
    CROSS_COMPILE=${TARGET_PREFIX} make PLAT=${UEFIMACHINE} DEBUG=0

    cd ${EDK2_DIR}/l-loader
    ln -s ${EDK2_DIR}/atf/build/${UEFIMACHINE}/release/bl1.bin
    ln -s ${EDK2_DIR}/atf/build/${UEFIMACHINE}/release/bl2.bin
    ln -s ${EDK2_DIR}/atf-fastboot/build/${UEFIMACHINE}/release/bl1.bin fastboot.bin
    make -f ${UEFIMACHINE}.mk recovery.bin
    make -f ${UEFIMACHINE}.mk l-loader.bin
    for ptable in linux-4g linux-8g; do
      PTABLE=${ptable} SECTOR_SIZE=512 bash -x generate_ptable.sh
      mv prm_ptable.img ptable-${ptable}.img
    done
}

do_install() {
    install -D -p -m0644 ${EDK2_DIR}/Build/HiKey/RELEASE_${AARCH64_TOOLCHAIN}/AARCH64/AndroidFastbootApp.efi ${D}/boot/EFI/BOOT/fastboot.efi
    install -D -p -m0644 ${EDK2_DIR}/Build/HiKey/RELEASE_${AARCH64_TOOLCHAIN}/FV/bl1.bin ${D}${libdir}/edk2/bl1.bin

    # Install grub configuration
    sed -e "s|@DISTRO_NAME|${DISTRO_NAME}|" \
        -e "s|@KERNEL_IMAGETYPE|${KERNEL_IMAGETYPE}|" \
        -e "s|@CMDLINE|${CMDLINE}|" \
        < ${WORKDIR}/grub.cfg.in \
        > ${WORKDIR}/grub.cfg
    install -D -p -m0644 ${WORKDIR}/grub.cfg ${D}/boot/grub/grub.cfg
}

# Create a 64M boot image. block size is 1024. (64*1024=65536)
BOOT_IMAGE_SIZE = "65536"
BOOT_IMAGE_BASE_NAME = "boot-${PKGV}-${PKGR}-${MACHINE}-${DATETIME}"
BOOT_IMAGE_BASE_NAME[vardepsexclude] = "DATETIME"

# HiKey boot image requires fastboot and grub EFI
# ensure we deploy grub-efi-bootaa64.efi before we try to create the boot image.
do_deploy[depends] += "grub-efi:do_deploy"
do_deploy:append() {
    cd ${EDK2_DIR}/l-loader
    install -D -p -m0644 l-loader.bin ${DEPLOYDIR}/bootloader/l-loader.bin
    install -D -p -m0644 recovery.bin ${DEPLOYDIR}/bootloader/recovery.bin
    cp -a ptable*.img ${DEPLOYDIR}/bootloader/

    # Ship nvme.img with UEFI binaries for convenience
    mkdir -p ${DEPLOYDIR}/bootloader/
    dd if=/dev/zero of=${DEPLOYDIR}/bootloader/nvme.img bs=128 count=1024

    # Create boot image
    mkfs.vfat -F32 -n "boot" -C ${DEPLOYDIR}/${BOOT_IMAGE_BASE_NAME}.uefi.img ${BOOT_IMAGE_SIZE}
    mmd -i ${DEPLOYDIR}/${BOOT_IMAGE_BASE_NAME}.uefi.img ::EFI
    mmd -i ${DEPLOYDIR}/${BOOT_IMAGE_BASE_NAME}.uefi.img ::EFI/BOOT
    mcopy -i ${DEPLOYDIR}/${BOOT_IMAGE_BASE_NAME}.uefi.img ${EDK2_DIR}/Build/HiKey/RELEASE_${AARCH64_TOOLCHAIN}/AARCH64/AndroidFastbootApp.efi ::EFI/BOOT/fastboot.efi
    mcopy -i ${DEPLOYDIR}/${BOOT_IMAGE_BASE_NAME}.uefi.img ${DEPLOY_DIR_IMAGE}/grub-efi-bootaa64.efi ::EFI/BOOT/grubaa64.efi
    chmod 644 ${DEPLOYDIR}/${BOOT_IMAGE_BASE_NAME}.uefi.img

    (cd ${DEPLOYDIR} && ln -sf ${BOOT_IMAGE_BASE_NAME}.uefi.img boot-${MACHINE}.uefi.img)

    # Fix up - move bootloader related files into a subdir
    mv ${DEPLOYDIR}/fip.bin ${DEPLOYDIR}/bootloader/
}
