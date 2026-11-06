require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

require 'digest'
require 'fileutils'
require 'tmpdir'

# TrackMeSDK.xcframework: the released binary for this SDK version, downloaded on the first `pod install` and checked
# against its SHA-256 (the same zip and checksum as the SwiftPM release). scripts/release-ios.sh writes both values.
# In this repo, `scripts/port-ios-sdk.sh local` puts a development build there instead (marker "local").
trackme_sdk_version = '0.3.2'
trackme_sdk_sha256 = '8908ed67fdf4d004a725742faaac8d118c13c6de55b5d6dcebbedc269ee51e2a'
frameworks = File.join(__dir__, 'ios/Frameworks')
marker = File.join(frameworks, 'TrackMeSDK.version')
installed = File.exist?(marker) ? File.read(marker).strip : nil
have = Dir.exist?(File.join(frameworks, 'TrackMeSDK.xcframework'))
unless have && (installed == 'local' || installed == trackme_sdk_version)
  url = "https://github.com/baato/trackme-sdk/releases/download/#{trackme_sdk_version}/TrackMeSDK.xcframework.zip"
  Dir.mktmpdir do |tmp|
    zip = File.join(tmp, 'sdk.zip')
    system('curl', '-fsSL', '-o', zip, url) or raise "TrackMeReactNative: couldn't download #{url}"
    raise "TrackMeReactNative: checksum mismatch for #{url}" unless Digest::SHA256.file(zip).hexdigest == trackme_sdk_sha256
    FileUtils.rm_rf(File.join(frameworks, 'TrackMeSDK.xcframework'))
    FileUtils.mkdir_p(frameworks)
    system('unzip', '-q', zip, '-d', frameworks) or raise "TrackMeReactNative: couldn't unzip #{zip}"
    File.write(marker, trackme_sdk_version)
  end
end
# The lock-screen code (TrackMeLiveActivity + the presenter), copied in by scripts/port-ios-sdk.sh and committed.
unless Dir.exist?(File.join(__dir__, 'ios/LiveActivity'))
  raise "TrackMeReactNative: ios/LiveActivity is missing. Run scripts/port-ios-sdk.sh local (repo root), then pod install."
end

Pod::Spec.new do |s|
  s.name         = "TrackMeReactNative"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = { :type => "Proprietary", :file => "LICENSE" }
  s.authors      = package["author"]
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/baato/trackme-sdk.git", :tag => s.version.to_s }
  # ios/LiveActivity/: the TrackMeLiveActivity package + the presenter, copied in by scripts/port-ios-sdk.sh.
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.weak_frameworks = "ActivityKit", "AppIntents", "WidgetKit"
  s.swift_version = "5.0"

  # The native SDK. Filled in by scripts/port-ios-sdk.sh (git-ignored): `local` from this repo's build during
  # development, or a release version's TrackMeSDK.xcframework.zip before publishing the package.
  s.vendored_frameworks = "ios/Frameworks/TrackMeSDK.xcframework"

  # The XCFramework has arm64 slices only (devices + Apple-silicon simulators).
  s.pod_target_xcconfig = { "EXCLUDED_ARCHS[sdk=iphonesimulator*]" => "x86_64" }

  # React Native's helper: codegen, React-Core and the new-architecture dependencies.
  install_modules_dependencies(s)
end
