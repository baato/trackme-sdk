const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

// The module lives one folder up ("@baato/trackme-react-native": "file:.."). Metro watches it, but must never load
// the module's own dev copies of react / react-native (../node_modules): two Reacts in one app break hooks.
const root = path.resolve(__dirname, '..');
const escape = (p) => p.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&');

/** @type {import('@react-native/metro-config').MetroConfig} */
const config = {
  watchFolders: [root],
  resolver: {
    blockList: [new RegExp(`^${escape(path.join(root, 'node_modules'))}\\/.*$`)],
    nodeModulesPaths: [path.join(__dirname, 'node_modules')],
    extraNodeModules: {
      react: path.join(__dirname, 'node_modules', 'react'),
      'react-native': path.join(__dirname, 'node_modules', 'react-native'),
    },
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
