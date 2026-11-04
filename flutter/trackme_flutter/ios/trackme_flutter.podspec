Pod::Spec.new do |s|
  s.name             = 'trackme_flutter'
  s.version          = '0.3.0'
  s.summary          = 'TrackMe live location sharing for Flutter (wraps the native TrackMe SDK).'
  s.homepage         = 'https://github.com/baato/trackme-sdk'
  s.license          = { :type => 'Proprietary', :file => '../LICENSE' }
  s.author           = 'Baato'
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform         = :ios, '15.0'
  s.swift_version    = '5.0'

  # The native SDK. Filled in by scripts/port-ios-sdk.sh (git-ignored): `local` from this repo's build during
  # development, or a release version's TrackMeSDK.xcframework.zip before publishing the package.
  s.vendored_frameworks = 'Frameworks/TrackMeSDK.xcframework'

  # The XCFramework has arm64 slices only (devices + Apple-silicon simulators).
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
end
