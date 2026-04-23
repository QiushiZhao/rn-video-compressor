import { validateInputUri, validateOutputUri } from './validate';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

describe('validateInputUri', () => {
  let existing: string;
  beforeAll(() => {
    existing = path.join(os.tmpdir(), `rnvc-in-${Date.now()}.mp4`);
    fs.writeFileSync(existing, 'x');
  });
  afterAll(() => fs.unlinkSync(existing));

  it('accepts existing file:// uri', () => {
    expect(() => validateInputUri('file://' + existing)).not.toThrow();
  });

  it('rejects non-file:// scheme', () => {
    expect(() => validateInputUri('content://media/foo')).toThrow(
      expect.objectContaining({ code: 'ERR_INPUT_NOT_FOUND' })
    );
  });

  it('rejects missing file', () => {
    expect(() => validateInputUri('file:///definitely/not/here.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_INPUT_NOT_FOUND' })
    );
  });
});

describe('validateOutputUri', () => {
  const tmp = os.tmpdir();

  it('accepts writable path ending in .mp4', () => {
    expect(() => validateOutputUri('file://' + path.join(tmp, 'out.mp4'))).not.toThrow();
  });

  it('accepts .MP4 (case-insensitive)', () => {
    expect(() => validateOutputUri('file://' + path.join(tmp, 'out.MP4'))).not.toThrow();
  });

  it('rejects non-file:// scheme', () => {
    expect(() => validateOutputUri('/no/scheme/out.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });

  it('rejects missing .mp4 suffix', () => {
    expect(() => validateOutputUri('file://' + path.join(tmp, 'out.mov'))).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });

  it('rejects parent dir that does not exist', () => {
    expect(() => validateOutputUri('file:///no/such/dir/out.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });
});
