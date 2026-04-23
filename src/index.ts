// Reexport the native module. On web, it will be resolved to RnVideoCompressorModule.web.ts
// and on native platforms to RnVideoCompressorModule.ts
export { default } from './RnVideoCompressorModule';
export { default as RnVideoCompressorView } from './RnVideoCompressorView';
export * from  './RnVideoCompressor.types';
