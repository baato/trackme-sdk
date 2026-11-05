#!/usr/bin/env ruby
# Adds the Widget Extension that draws the TrackMe Live Activity (lock screen + Dynamic Island) to an iOS app
# project: a `TrackMeWidget` target with a one-file entry point, compiling the TrackMeLiveActivity package sources,
# embedded into the app, plus NSSupportsLiveActivities in the app's Info.plist. Safe to run twice.
#
# Apple only lets a Live Activity's UI live in the host app's own Widget Extension, so no SDK can ship it; this
# script is the one step. Without the extension, TrackMe shows sharing as a notification instead.
#
#   ruby scripts/ios-add-widget-extension.rb <path/to/App.xcodeproj> <app target> <TrackMeLiveActivity sources dir>
#
# The sources dir is relative to the .xcodeproj's folder, e.g. for this repo's examples:
#   ruby scripts/ios-add-widget-extension.rb flutter/trackme_flutter/example/ios/Runner.xcodeproj Runner \
#        ../../../../TrackMeLiveActivity/Sources/TrackMeLiveActivity
# Uses the xcodeproj gem that comes with CocoaPods.
require 'fileutils'
require 'xcodeproj'

project_path, app_name, sources = ARGV
abort File.read(__FILE__).lines.grep(/^#   ruby/).first.sub('#', 'usage:') unless sources

NAME = 'TrackMeWidget'
project = Xcodeproj::Project.open(project_path)
app = project.targets.find { |t| t.name == app_name } or abort "✘ no target #{app_name} in #{project_path}"
if project.targets.any? { |t| t.name == NAME }
  puts "✔ #{NAME} is already in #{project_path}"
  exit 0
end

ios_dir = File.dirname(project_path)
swift_files = Dir[File.join(ios_dir, sources, '*.swift')].sort
abort "✘ no .swift files in #{File.join(ios_dir, sources)}" if swift_files.empty?

# ---- files on disk ----
widget_dir = File.join(ios_dir, NAME)
FileUtils.mkdir_p(widget_dir)
File.write(File.join(widget_dir, 'TrackMeWidgets.swift'), <<~SWIFT)
  import SwiftUI
  import WidgetKit

  /// Widget Extension entry point. The whole Live Activity UI is TrackMeLiveActivity's (compiled into this target).
  @main
  struct TrackMeWidgets: WidgetBundle {
      var body: some Widget {
          TrackMeLiveActivityWidget()
      }
  }
SWIFT
File.write(File.join(widget_dir, 'Info.plist'), <<~PLIST)
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0">
  <dict>
  	<key>CFBundleDevelopmentRegion</key>
  	<string>$(DEVELOPMENT_LANGUAGE)</string>
  	<key>CFBundleDisplayName</key>
  	<string>TrackMe</string>
  	<key>CFBundleExecutable</key>
  	<string>$(EXECUTABLE_NAME)</string>
  	<key>CFBundleIdentifier</key>
  	<string>$(PRODUCT_BUNDLE_IDENTIFIER)</string>
  	<key>CFBundleInfoDictionaryVersion</key>
  	<string>6.0</string>
  	<key>CFBundleName</key>
  	<string>$(PRODUCT_NAME)</string>
  	<key>CFBundlePackageType</key>
  	<string>$(PRODUCT_BUNDLE_PACKAGE_TYPE)</string>
  	<key>CFBundleShortVersionString</key>
  	<string>$(MARKETING_VERSION)</string>
  	<key>CFBundleVersion</key>
  	<string>$(CURRENT_PROJECT_VERSION)</string>
  	<key>NSExtension</key>
  	<dict>
  		<key>NSExtensionPointIdentifier</key>
  		<string>com.apple.widgetkit-extension</string>
  	</dict>
  </dict>
  </plist>
PLIST

# ---- the target ----
widget = project.new_target(:app_extension, NAME, :ios, '16.1', nil, :swift)
widget.build_configurations.each do |config|
  app_config = app.build_configurations.find { |c| c.name == config.name } || app.build_configurations.first
  app_settings = app_config.build_settings
  # The extension's id must start with the app's. Templates write it with $(PRODUCT_NAME…), which here would
  # name the extension, so resolve that to the app target's name.
  app_id = app_settings['PRODUCT_BUNDLE_IDENTIFIER'].to_s
                                                    .gsub('$(PRODUCT_NAME:rfc1034identifier)', app.name.gsub(/[^A-Za-z0-9-]/, '-'))
                                                    .gsub('$(PRODUCT_NAME)', app.name)
  s = config.build_settings
  s['PRODUCT_BUNDLE_IDENTIFIER'] = "#{app_id}.#{NAME}"
  s['PRODUCT_NAME'] = '$(TARGET_NAME)'
  s['INFOPLIST_FILE'] = "#{NAME}/Info.plist"
  s['GENERATE_INFOPLIST_FILE'] = 'NO'
  s['IPHONEOS_DEPLOYMENT_TARGET'] = '16.1'
  s['SWIFT_VERSION'] = '5.0'
  s['TARGETED_DEVICE_FAMILY'] = '1,2'
  s['CODE_SIGN_STYLE'] = 'Automatic'
  s['DEVELOPMENT_TEAM'] = app_settings['DEVELOPMENT_TEAM'] if app_settings['DEVELOPMENT_TEAM']
  s['SKIP_INSTALL'] = 'YES'
  s['APPLICATION_EXTENSION_API_ONLY'] = 'YES'
  s['LD_RUNPATH_SEARCH_PATHS'] = ['$(inherited)', '@executable_path/Frameworks', '@executable_path/../../Frameworks']
  s['MARKETING_VERSION'] = '1.0'
  s['CURRENT_PROJECT_VERSION'] = '1'
end
# Signing team chosen in Xcode's UI is stored on the target too.
attributes = project.root_object.attributes['TargetAttributes'] ||= {}
app_attributes = attributes[app.uuid] || {}
attributes[widget.uuid] = { 'CreatedOnToolsVersion' => '15.0' }
attributes[widget.uuid]['DevelopmentTeam'] = app_attributes['DevelopmentTeam'] if app_attributes['DevelopmentTeam']

group = project.main_group.new_group(NAME, NAME)
entry = group.new_reference('TrackMeWidgets.swift')
group.new_reference('Info.plist')
package_group = group.new_group('TrackMeLiveActivity', File.join('..', sources))
package_refs = swift_files.map { |f| package_group.new_reference(File.basename(f)) }
widget.add_file_references([entry, *package_refs])

# ---- embed it in the app ----
app.add_dependency(widget)
embed = app.copy_files_build_phases.find { |p| p.dst_subfolder_spec == '13' } ||
        app.new_copy_files_build_phase('Embed Foundation Extensions')
embed.dst_subfolder_spec = '13' # PlugIns
embed.dst_path = ''
embed.add_file_reference(widget.product_reference, true).settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }
# Right after Resources: later script phases (Flutter's "Thin Binary", CocoaPods' embeds) would otherwise form a
# build cycle with the extension.
app.build_phases.delete(embed)
resources = app.build_phases.index { |p| p.is_a?(Xcodeproj::Project::Object::PBXResourcesBuildPhase) }
app.build_phases.insert((resources || app.build_phases.size - 1) + 1, embed)

project.save
puts "✔ added #{NAME} to #{project_path} (bundle id #{widget.build_configurations.first.build_settings['PRODUCT_BUNDLE_IDENTIFIER']})"

# ---- the app's Info.plist ----
plist = app.build_configurations.first.build_settings['INFOPLIST_FILE'].to_s.gsub('$(SRCROOT)/', '')
plist_path = File.join(ios_dir, plist)
if File.exist?(plist_path)
  system('plutil', '-replace', 'NSSupportsLiveActivities', '-bool', 'YES', plist_path) or abort "✘ couldn't edit #{plist_path}"
  puts "✔ NSSupportsLiveActivities = YES in #{plist_path}"
else
  puts "! add NSSupportsLiveActivities = YES to the app's Info.plist (#{plist_path} not found)"
end
puts 'Next: pod install, then build the app (its scheme builds the extension too).'
