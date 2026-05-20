import { debugLog, debugWarn } from './debug.js';

export interface CompressOptions {
  maxDim?: number;
  quality?: number;
  minSizeBytes?: number;
}

export interface CompressResult {
  base64: string;
  mediaType: string;
  originalSize: number;
  compressedSize: number;
}

const DEFAULTS: Required<CompressOptions> = {
  maxDim: 2048,
  quality: 0.8,
  minSizeBytes: 200 * 1024,
};

export async function compressImage(
  blob: Blob,
  opts: CompressOptions = {}
): Promise<CompressResult> {
  const { maxDim, quality, minSizeBytes } = { ...DEFAULTS, ...opts };
  const originalSize = blob.size;

  if (!blob.type.startsWith('image/') || blob.size < minSizeBytes) {
    return {
      base64: await blobToBase64(blob),
      mediaType: blob.type || 'application/octet-stream',
      originalSize,
      compressedSize: originalSize,
    };
  }

  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(blob, { imageOrientation: 'from-image' });
  } catch (e) {
    debugWarn('[imageCompressor] createImageBitmap failed, using original:', e);
    return {
      base64: await blobToBase64(blob),
      mediaType: blob.type,
      originalSize,
      compressedSize: originalSize,
    };
  }

  const scale = Math.min(1, maxDim / Math.max(bitmap.width, bitmap.height));
  const targetW = Math.max(1, Math.round(bitmap.width * scale));
  const targetH = Math.max(1, Math.round(bitmap.height * scale));

  const keepAlpha = blob.type === 'image/png';
  const outType = keepAlpha ? 'image/png' : 'image/jpeg';

  let compressedBlob: Blob;
  try {
    compressedBlob = await drawAndEncode(bitmap, targetW, targetH, outType, quality, keepAlpha);
  } catch (e) {
    debugWarn('[imageCompressor] encode failed, using original:', e);
    bitmap.close();
    return {
      base64: await blobToBase64(blob),
      mediaType: blob.type,
      originalSize,
      compressedSize: originalSize,
    };
  } finally {
    bitmap.close();
  }

  if (compressedBlob.size >= originalSize) {
    debugLog(
      `[imageCompressor] compressed (${compressedBlob.size}) >= original (${originalSize}), keeping original`
    );
    return {
      base64: await blobToBase64(blob),
      mediaType: blob.type,
      originalSize,
      compressedSize: originalSize,
    };
  }

  const base64 = await blobToBase64(compressedBlob);
  debugLog(
    `[imageCompressor] ${blob.type} ${originalSize}B -> ${outType} ${compressedBlob.size}B (${Math.round(
      (1 - compressedBlob.size / originalSize) * 100
    )}% off, ${targetW}x${targetH})`
  );
  return {
    base64,
    mediaType: outType,
    originalSize,
    compressedSize: compressedBlob.size,
  };
}

async function drawAndEncode(
  bitmap: ImageBitmap,
  width: number,
  height: number,
  type: 'image/png' | 'image/jpeg',
  quality: number,
  keepAlpha: boolean
): Promise<Blob> {
  if (typeof OffscreenCanvas !== 'undefined') {
    const canvas = new OffscreenCanvas(width, height);
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('2d context unavailable');
    if (!keepAlpha) {
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, width, height);
    }
    ctx.drawImage(bitmap, 0, 0, width, height);
    return await canvas.convertToBlob({
      type,
      quality: type === 'image/jpeg' ? quality : undefined,
    });
  }

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('2d context unavailable');
  if (!keepAlpha) {
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, width, height);
  }
  ctx.drawImage(bitmap, 0, 0, width, height);
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('canvas.toBlob returned null'))),
      type,
      type === 'image/jpeg' ? quality : undefined
    );
  });
}

async function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result;
      if (typeof result !== 'string') {
        reject(new Error('FileReader result is not a string'));
        return;
      }
      const commaIdx = result.indexOf(',');
      resolve(commaIdx === -1 ? result : result.substring(commaIdx + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('FileReader error'));
    reader.readAsDataURL(blob);
  });
}

export function base64ToBlob(base64: string, mediaType: string): Blob {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new Blob([bytes], { type: mediaType });
}

export function inferExtensionFromMediaType(mediaType: string, fallback = 'png'): string {
  if (!mediaType || !mediaType.includes('/')) return fallback;
  const sub = mediaType.split('/')[1];
  if (sub === 'jpeg') return 'jpg';
  return sub || fallback;
}
