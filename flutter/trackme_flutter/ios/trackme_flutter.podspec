require 'digest'
require 'fileutils'
require 'tmpdir'

# TrackMeSDK.xcframework: the released binary for this SDK version, downloaded on the first `pod install` and checked
# against its SHA-256 (the same zip and checksum as the SwiftPM release). scripts/release-ios.sh writes both values.
# In this repo, `scripts/port-ios-sdk.sh local` puts a development build there instead (marker "local").
trackme_sdk_version = '0.3.0'
trackme_sdk_sha256 = '8908ed67fdf4d004a725742faaac8d118c13c6de55b5d6dcebbedc269ee51e2a'
frameworks = File.join(__dir__, 'Frameworks')
marker = File.join(frameworks, 'TrackMeSDK.version')
installed = File.exist?(marker) ? File.read(marker).strip : nil
have = Dir.exist?(File.join(frameworks, 'TrackMeSDK.xcframework'))
unless have && (installed == 'local' || installed == trackme_sdk_version)
  url = "https://github.com/baato/trackme-sdk/releases/download/#{trackme_sdk_version}/TrackMeSDK.xcframework.zip"
  Dir.mktmpdir do |tmp|
    zip = File.join(tmp, 'sdk.zip')
    system('curl', '-fsSL', '-o', zip, url) or raise "trackme_flutter: couldn't download #{url}"
    raise "trackme_flutter: checksum mismatch for #{url}" unless Digest::SHA256.file(zip).hexdigest == trackme_sdk_sha256
    FileUtils.rm_rf(File.join(frameworks, 'TrackMeSDK.xcframework'))
    FileUtils.mkdir_p(frameworks)
    system('unzip', '-q', zip, '-d', frameworks) or raise "trackme_flutter: couldn't unzip #{zip}"
    File.write(marker, trackme_sdk_version)
  end
end
# The lock-screen code (TrackMeLiveActivity + the presenter), copied in by scripts/port-ios-sdk.sh and committed.
unless Dir.exist?(File.join(__dir__, 'LiveActivity'))
  raise "trackme_flutter: LiveActivity is missing. Run scripts/port-ios-sdk.sh local (repo root), then pod install."
end

Pod::Spec.new do |s|
  s.name             = 'trackme_flutter'
  s.version          = '0.3.0'
  s.summary          = 'TrackMe live location sharing for Flutter (wraps the native TrackMe SDK).'
  s.homepage         = 'https://github.com/baato/trackme-sdk'
  s.license          = { :type => 'Proprietary', :file => '../LICENSE' }
  s.author           = 'Baato'
  s.source           = { :path => '.' }
  # LiveActivity/: the TrackMeLiveActivity package + the presenter, copied in by scripts/port-ios-sdk.sh.
  s.source_files     = 'Classes/**/*', 'LiveActivity/**/*.swift'
  s.weak_frameworks  = 'ActivityKit', 'AppIntents', 'WidgetKit'
  s.dependency 'Flutter'
  s.platform         = :ios, '15.0'
  s.swift_version    = '5.0'
  # Static, like a Swift package: iOS only finds the Live Activity's Pause / Resume / Stop App Intents in code linked
  # into the app itself. Inside a dynamic framework (Flutter's use_frameworks!) the buttons do nothing.
  s.static_framework = true

  # The native SDK. Filled in by scripts/port-ios-sdk.sh (git-ignored): `local` from this repo's build during
  # development, or a release version's TrackMeSDK.xcframework.zip before publishing the package.
  s.vendored_frameworks = 'Frameworks/TrackMeSDK.xcframework'

  # The XCFramework has arm64 slices only (devices + Apple-silicon simulators).
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
end
