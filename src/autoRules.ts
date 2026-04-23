export function roundEven(x: number): number {
  return Math.round(x / 2) * 2;
}

const SHORT_EDGE_CAP = 720;
const BITRATE_FACTOR = 3;

export function computeTargetSize(
  sourceWidth: number,
  sourceHeight: number
): { width: number; height: number } {
  const shortEdge = Math.min(sourceWidth, sourceHeight);
  if (shortEdge <= SHORT_EDGE_CAP) {
    return { width: sourceWidth, height: sourceHeight };
  }
  const scale = SHORT_EDGE_CAP / shortEdge;
  return {
    width: roundEven(sourceWidth * scale),
    height: roundEven(sourceHeight * scale),
  };
}

export function shouldSkip(
  sourceWidth: number,
  sourceHeight: number,
  sourceBitrate: number
): boolean {
  const shortEdge = Math.min(sourceWidth, sourceHeight);
  if (shortEdge > SHORT_EDGE_CAP) return false;
  const threshold = sourceWidth * sourceHeight * BITRATE_FACTOR;
  return sourceBitrate <= threshold;
}

export function computeBitrate(width: number, height: number): number {
  return Math.ceil(width * height * BITRATE_FACTOR);
}
