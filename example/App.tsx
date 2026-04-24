import { useState } from 'react';
import { Button, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
import {
  compressAuto,
  compress,
  CompressResult,
} from '@qiushizhao/rn-video-compressor';

async function pickVideo(): Promise<string | null> {
  const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
  if (!perm.granted) return null;
  const res = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ImagePicker.MediaTypeOptions.Videos,
    allowsEditing: false,
  });
  if (res.canceled) return null;
  return res.assets[0].uri;
}

function buildOutputPath(): string {
  const name = `out-${Date.now()}.mp4`;
  return `${FileSystem.documentDirectory}${name}`;
}

async function fileSize(uri: string): Promise<number> {
  const info = await FileSystem.getInfoAsync(uri);
  return info.exists ? (info.size ?? 0) : 0;
}

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const [progress, setProgress] = useState(0);

  const append = (line: string) => setLog((l) => [...l, line]);

  const runAuto = async () => {
    setProgress(0);
    try {
      const input = await pickVideo();
      if (!input) return;
      const output = buildOutputPath();
      const inSize = await fileSize(input);
      append(`auto: input=${input} (${inSize} bytes), output=${output}`);
      const started = Date.now();
      const result: CompressResult = await compressAuto(input, output, {
        onProgress: setProgress,
      });
      const outSize = await fileSize(result.uri);
      append(`auto done in ${Date.now() - started}ms: skipped=${result.skipped}, out=${outSize} bytes`);
    } catch (e: any) {
      append(`auto error: ${e?.code ?? 'UNKNOWN'} ${e?.message ?? e}`);
    }
  };

  const runCustom = async () => {
    setProgress(0);
    try {
      const input = await pickVideo();
      if (!input) return;
      const output = buildOutputPath();
      const started = Date.now();
      const result = await compress(input, output, {
        width: 640,
        height: 360,
        videoBitrate: 800_000,
        onProgress: setProgress,
      });
      const outSize = await fileSize(result.uri);
      append(`custom done in ${Date.now() - started}ms: out=${outSize} bytes`);
    } catch (e: any) {
      append(`custom error: ${e?.code ?? 'UNKNOWN'} ${e?.message ?? e}`);
    }
  };

  const runBadOutput = async () => {
    try {
      const input = await pickVideo();
      if (!input) return;
      await compressAuto(input, 'file:///tmp/out.mov');
    } catch (e: any) {
      append(`expected error: ${e?.code} ${e?.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.buttons}>
        <Button title="Pick & compressAuto" onPress={runAuto} />
        <Button title="Pick & compress (640x360 @ 800kbps)" onPress={runCustom} />
        <Button title="Bad output path (expect error)" onPress={runBadOutput} />
      </View>
      <Text>progress: {(progress * 100).toFixed(1)}%</Text>
      <ScrollView style={styles.log}>
        {log.map((line, i) => (
          <Text key={i} style={styles.logLine}>{line}</Text>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 16 },
  buttons: { gap: 8, marginBottom: 16 },
  log: { flex: 1, borderWidth: 1, borderColor: '#ccc', padding: 8 },
  logLine: { fontFamily: 'Menlo', fontSize: 11, marginBottom: 4 },
});
