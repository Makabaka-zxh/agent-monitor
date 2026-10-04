"use strict";
// Image and QR processing stays on this device; no camera frames are uploaded.
window.MonitorFeatures = (() => {
  let decoder;
  function loadDecoder() {
    if (window.jsQR) return Promise.resolve(window.jsQR);
    if (!decoder) decoder = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/assets/vendor/jsQR-1.4.0.js';
      script.onload = () => resolve(window.jsQR);
      script.onerror = () => { decoder = null; script.remove(); reject(new Error('扫码组件未加载，请联网重试。')); };
      document.head.append(script);
    });
    return decoder;
  }
  async function avatar(file) {
    if (!file || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) throw new Error('请选择 JPG、PNG 或 WebP 图片。');
    if (file.size > 10 * 1024 * 1024) throw new Error('请选择小于 10 MB 的图片。');
    const bitmap = await createImageBitmap(file);
    try {
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 256;
      const context = canvas.getContext('2d');
      context.fillStyle = '#eef2f3'; context.fillRect(0, 0, 256, 256);
      const edge = Math.min(bitmap.width, bitmap.height);
      context.drawImage(bitmap, (bitmap.width-edge)/2, (bitmap.height-edge)/2, edge, edge, 0, 0, 256, 256);
      return canvas.toDataURL('image/jpeg', .88);
    } finally { bitmap.close(); }
  }
  function pairingId(text) {
    let url;
    try { url = new URL(text); } catch (_) { throw new Error('这不是电脑配对二维码。'); }
    const match = url.hash.match(/^#\/pairing-confirm\/([A-Za-z0-9_-]{20,128})$/);
    if (url.origin !== location.origin || url.username || url.password || url.search || url.pathname !== '/' || !match) {
      throw new Error('请扫描本服务中电脑连接程序显示的二维码。');
    }
    return match[1];
  }
  async function readQr(source) {
    const decode = await loadDecoder();
    const width = source.videoWidth || source.width, height = source.videoHeight || source.height;
    if (!width || !height) return null;
    const scale = Math.min(1, 900 / Math.max(width, height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(width * scale); canvas.height = Math.round(height * scale);
    const context = canvas.getContext('2d', {willReadFrequently:true});
    context.drawImage(source, 0, 0, canvas.width, canvas.height);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
    return decode(pixels.data, pixels.width, pixels.height, {inversionAttempts:'attemptBoth'})?.data || null;
  }
  return {avatar, pairingId, readQr};
})();
