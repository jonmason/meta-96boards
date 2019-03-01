DESCRIPTION = "ARM Trusted Firmware Juno"
LICENSE = "BSD"
LIC_FILES_CHKSUM = "file://license.rst;md5=33065335ea03d977d0569f270b39603e"
DEPENDS += "u-boot-juno zip-native"
SRCREV = "b762fc7481c66b64eb98b6ff694d569e66253973"

SRC_URI = "git://github.com/ARM-software/arm-trusted-firmware.git;protocol=https;name=atf;branch=master \
    http://releases.linaro.org/members/arm/platforms/18.04/juno-latest-oe-uboot.zip;name=junofip;subdir=juno-oe-uboot \
"
SRC_URI[junofip.md5sum] = "1cf91f8719dbf966b0d7c2b5d6135792"
SRC_URI[junofip.sha256sum] = "ff01941725f1f67910817a8ec88be9a2db9d132c50b2bb4d13291c471f30b91f"

S = "${WORKDIR}/git"

inherit deploy

require atf.inc

COMPATIBLE_MACHINE = "juno"

# ATF requires u-boot.bin file. Ensure it's deployed before we compile.
do_compile[depends] += "u-boot-juno:do_deploy"

# Building for Juno requires a special SCP firmware to be packed with FIP.
# You can refer to the documentation here:
# https://github.com/ARM-software/arm-trusted-firmware/blob/master/docs/user-guide.rst#building-a-fip-for-juno-and-fvp
# This must be obtained from the Arm deliverables as released by Linaro:
# https://community.arm.com/dev-platforms/b/documents/posts/linaro-release-notes-deprecated
do_compile() {
    oe_runmake \
      CROSS_COMPILE=${TARGET_PREFIX} \
      all \
      fip \
      PLAT=${COMPATIBLE_MACHINE} \
      SPD=none \
      SCP_BL2=${WORKDIR}/juno-oe-uboot/SOFTWARE/scp_bl2.bin \
      BL33=${DEPLOY_DIR_IMAGE}/u-boot.bin

    # Generate new FIP using our U-boot
    ./tools/fiptool/fiptool update \
      --nt-fw ${DEPLOY_DIR_IMAGE}/u-boot.bin \
      build/${COMPATIBLE_MACHINE}/release/fip.bin
}

# Ensure we deploy kernel/dtb before we create the recovery image.
do_deploy[depends] += "virtual/kernel:do_deploy"
do_deploy() {
    # Create new recovery image
    cp -a \
      build/${COMPATIBLE_MACHINE}/release/bl1.bin \
      build/${COMPATIBLE_MACHINE}/release/fip.bin \
      ${WORKDIR}/juno-oe-uboot/SOFTWARE/

    cp -aL ${DEPLOY_DIR_IMAGE}/Image \
    ${WORKDIR}/juno-oe-uboot/SOFTWARE/Image

    cp -aL ${DEPLOY_DIR_IMAGE}/Image-juno.dtb \
    ${WORKDIR}/juno-oe-uboot/SOFTWARE/juno.dtb

    cp -aL ${DEPLOY_DIR_IMAGE}/Image-juno-r1.dtb \
    ${WORKDIR}/juno-oe-uboot/SOFTWARE/juno-r1.dtb

    if [ -L ${DEPLOY_DIR_IMAGE}/Image-juno-r2.dtb ]; then
      cp -aL ${DEPLOY_DIR_IMAGE}/Image-juno-r2.dtb \
      ${WORKDIR}/juno-oe-uboot/SOFTWARE/juno-r2.dtb
    else # Deploy a dummy r2 dtb, copy of Juno r1 dtb
      cp -aL ${DEPLOY_DIR_IMAGE}/Image-juno-r1.dtb \
      ${WORKDIR}/juno-oe-uboot/SOFTWARE/juno-r2.dtb

      dt_src=$(readlink ${DEPLOY_DIR_IMAGE}/Image-juno-r1.dtb)
      dt_dst=$(echo ${dt_src} | sed -e 's/juno-r1/juno-r2/')
      cp -a ${DEPLOY_DIR_IMAGE}/${dt_src} ${DEPLOY_DIR_IMAGE}/${dt_dst}
    fi

    # Move the ramdisk up in NOR flash to give more space for a larger kernel.
    # This also means that we have less space for the ramdisk, however, on OE
    # systems, we use a stub ramdisk of 576 bytes, so we don't need much space.
    # This will probably break Android, which uses a 1.5MB ramdisk.
    sed -i -e 's/^NOR4ADDRESS:.*/NOR4ADDRESS: 0x02200000          ;Image Flash Address/g' \
      ${WORKDIR}/juno-oe-uboot/SITE1/*/images.txt

    cd ${WORKDIR}/juno-oe-uboot/
    zip -r ${WORKDIR}/juno-oe-uboot.zip .

    # Deploy recovery package
    install -D -p -m0644 ${WORKDIR}/juno-oe-uboot.zip ${DEPLOYDIR}/juno-oe-uboot.zip
}

addtask deploy before do_build after do_compile
