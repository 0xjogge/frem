{
  description = "Frem native Android development environment";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/7fd4004d0511991fff3a0524856a9be09e28c5f0";
  outputs = { self, nixpkgs }: let
    system = "x86_64-linux";
    pkgs = import nixpkgs { inherit system; config = { allowUnfree = true; android_sdk.accept_license = true; }; };
    android = pkgs.androidenv.composeAndroidPackages {
      platformVersions = [ "37" ];
      buildToolsVersions = [ "36.0.0" ];
      cmakeVersions = [ ];
      includeEmulator = false;
      includeNDK = false;
      includeSources = false;
      includeSystemImages = false;
    };
  in {
    devShells.${system}.default = pkgs.mkShell {
      packages = [ pkgs.jdk21 pkgs.python3 pkgs.nodejs pkgs.android-tools pkgs.scrcpy android.androidsdk ];
      JAVA_HOME = "${pkgs.jdk21.home}";
      ANDROID_HOME = "${android.androidsdk}/libexec/android-sdk";
      ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";
      GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${android.androidsdk}/libexec/android-sdk/build-tools/36.0.0/aapt2";
    };
  };
}
