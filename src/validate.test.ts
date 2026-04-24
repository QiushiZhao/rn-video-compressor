import { validateInputUri, validateOutputUri } from './validate';

describe('validateInputUri', () => {
  it('accepts file:// uri', () => {
    expect(() => validateInputUri('file:///tmp/foo.mp4')).not.toThrow();
  });

  it('rejects non-file:// scheme', () => {
    expect(() => validateInputUri('content://media/foo')).toThrow(
      expect.objectContaining({ code: 'ERR_INPUT_NOT_FOUND' })
    );
  });

  it('rejects empty path after scheme', () => {
    expect(() => validateInputUri('file://')).toThrow(
      expect.objectContaining({ code: 'ERR_INPUT_NOT_FOUND' })
    );
  });
});

describe('validateOutputUri', () => {
  it('accepts file:// path ending in .mp4', () => {
    expect(() => validateOutputUri('file:///tmp/out.mp4')).not.toThrow();
  });

  it('accepts .MP4 (case-insensitive)', () => {
    expect(() => validateOutputUri('file:///tmp/out.MP4')).not.toThrow();
  });

  it('rejects non-file:// scheme', () => {
    expect(() => validateOutputUri('/no/scheme/out.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });

  it('rejects missing .mp4 suffix', () => {
    expect(() => validateOutputUri('file:///tmp/out.mov')).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });
});
