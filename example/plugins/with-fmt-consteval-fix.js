const { withPodfile } = require('@expo/config-plugins');

const MARKER = '# fmt-consteval-fix';
const SNIPPET = `
    ${MARKER}
    installer.pods_project.targets.each do |target|
      if target.name == 'fmt' || target.name == 'FMT'
        target.build_configurations.each do |config|
          config.build_settings['GCC_PREPROCESSOR_DEFINITIONS'] ||= ['$(inherited)']
          config.build_settings['GCC_PREPROCESSOR_DEFINITIONS'] << 'FMT_USE_CONSTEVAL=0'
        end
      end
    end
`;

// CocoaPods disallows multiple top-level post_install hooks, so inject our
// workaround INSIDE the existing `post_install do |installer| ... end` block
// emitted by the Expo template.
module.exports = function withFmtConstevalFix(config) {
  return withPodfile(config, (cfg) => {
    const contents = cfg.modResults.contents;
    if (contents.includes(MARKER)) {
      return cfg;
    }

    const hookRegex = /(post_install do \|installer\|\n)/;
    if (!hookRegex.test(contents)) {
      throw new Error(
        'with-fmt-consteval-fix: could not find existing `post_install do |installer|` block in Podfile'
      );
    }

    cfg.modResults.contents = contents.replace(hookRegex, `$1${SNIPPET}`);
    return cfg;
  });
};
