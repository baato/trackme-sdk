require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "TrackMeReactNative"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = { :type => "Proprietary", :file => "LICENSE" }
  s.authors      = package["author"]
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/baato/trackme-sdk.git", :tag => s.version.to_s }
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.swift_version = "5.0"

  # The native SDK. Filled in by scripts/port-ios-sdk.sh (git-ignored): `local` from this repo's build during
  # development, or a release version's TrackMeSDK.xcframework.zip before publishing the package.
  s.vendored_frameworks = "ios/Frameworks/TrackMeSDK.xcframework"

  # The XCFramework has arm64 slices only (devices + Apple-silicon simulators).
  s.pod_target_xcconfig = { "EXCLUDED_ARCHS[sdk=iphonesimulator*]" => "x86_64" }

  # React Native's helper: codegen, React-Core and the new-architecture dependencies.
  install_modules_dependencies(s)
end
