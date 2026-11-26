#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

cd "$(dirname "$0")/../../../.."
out=${OUT_DIR:-out}
base=packages/apps/BtHelper
src=$base/src/com/android/bluetooth/bthelper
app=$out/soong/.intermediates/$base/BtHelper/android_common/kotlin/BtHelper.jar
bluetooth=$out/soong/.intermediates/packages/modules/Bluetooth/framework/framework-bluetooth.impl/android_common/turbine/framework-bluetooth.jar
kotlin=external/kotlinc
export JAVA_HOME="${JAVA_HOME:-$PWD/prebuilts/jdk/jdk21/linux-x86}"

for jar in "$app" "$bluetooth"; do
    if [[ ! -f $jar ]]; then
        printf 'Missing %s. Build the BtHelper module first.\n' "$jar" >&2
        exit 1
    fi
done

classpath="prebuilts/sdk/current/public/android.jar:$bluetooth:$app:$kotlin/lib/kotlinx-coroutines-core-jvm.jar"
tmp=$(mktemp -d)
trap 'rm -r "$tmp"' EXIT
"$kotlin/bin/kotlinc" -classpath "$classpath" \
    "$src/pods/AccessoryControls.kt" "$src/utils/AACPManager.kt" \
    "$src/utils/BluetoothConnectionManager.kt" "$base/tests/AccessoryControlsTest.kt" \
    -d "$tmp/test.jar"
"$JAVA_HOME/bin/java" -cp "$tmp/test.jar:$classpath:$kotlin/lib/kotlin-stdlib.jar" \
    com.android.bluetooth.bthelper.pods.AccessoryControlsTestKt
