{ pkgs ? import <nixpkgs> {
    config.android_sdk.accept_license = true;
  }
}:

let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "36" ];
    buildToolsVersions = [ "36.0.0" ];

    includeEmulator = false;
    includeNDK = false;
  };

  androidSdk = android.androidsdk;
in

(pkgs.buildFHSEnv {
  name = "runlog-android-env";

  targetPkgs = pkgs: [
    pkgs.jdk17
    pkgs.curl
    pkgs.unzip
    pkgs.android-tools
    androidSdk
  ];

  profile = ''
    export JAVA_HOME=${pkgs.jdk17.home}
    export ANDROID_HOME=${androidSdk}/libexec/android-sdk
    export ANDROID_SDK_ROOT="$ANDROID_HOME"

    export PATH="$ANDROID_HOME/platform-tools:$PATH"
  '';

  runScript = "bash";
}).env
