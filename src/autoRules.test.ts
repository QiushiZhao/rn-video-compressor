import { roundEven, computeTargetSize, shouldSkip, computeBitrate } from './autoRules';

describe('roundEven', () => {
  it('keeps even numbers', () => {
    expect(roundEven(720)).toBe(720);
  });
  it('rounds odd numbers to nearest even', () => {
    expect(roundEven(721)).toBe(722);
    expect(roundEven(719)).toBe(720);
  });
  it('handles non-integer input', () => {
    expect(roundEven(405.5)).toBe(406);
  });
});

describe('computeTargetSize (short edge cap 720)', () => {
  it('keeps size when short edge <= 720', () => {
    expect(computeTargetSize(1280, 720)).toEqual({ width: 1280, height: 720 });
    expect(computeTargetSize(640, 480)).toEqual({ width: 640, height: 480 });
  });
  it('scales 1920x1080 (landscape) to 1280x720', () => {
    expect(computeTargetSize(1920, 1080)).toEqual({ width: 1280, height: 720 });
  });
  it('scales 1080x1920 (portrait) to 720x1280', () => {
    expect(computeTargetSize(1080, 1920)).toEqual({ width: 720, height: 1280 });
  });
  it('scales 3840x2160 (4K landscape) to 1280x720', () => {
    expect(computeTargetSize(3840, 2160)).toEqual({ width: 1280, height: 720 });
  });
  it('produces even dimensions', () => {
    const { width, height } = computeTargetSize(1281, 1921);
    expect(width % 2).toBe(0);
    expect(height % 2).toBe(0);
  });
});

describe('shouldSkip', () => {
  it('true when short edge <= 720 AND bitrate <= w*h*3', () => {
    expect(shouldSkip(1280, 720, 1280 * 720 * 3)).toBe(true);
    expect(shouldSkip(640, 480, 100_000)).toBe(true);
  });
  it('false when short edge > 720', () => {
    expect(shouldSkip(1920, 1080, 1_000_000)).toBe(false);
  });
  it('false when bitrate exceeds w*h*3', () => {
    expect(shouldSkip(1280, 720, 1280 * 720 * 3 + 1)).toBe(false);
  });
  it('treats unknown bitrate (0) as skippable only if dims fit', () => {
    expect(shouldSkip(1280, 720, 0)).toBe(true);
    expect(shouldSkip(1920, 1080, 0)).toBe(false);
  });
});

describe('computeBitrate', () => {
  it('equals ceil(w * h * 3)', () => {
    expect(computeBitrate(1280, 720)).toBe(1280 * 720 * 3);
    expect(computeBitrate(720, 1280)).toBe(720 * 1280 * 3);
  });
});
