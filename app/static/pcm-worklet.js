class PCMCapture extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    this.size = opts.frameSize || 2048;
    this.buf = new Float32Array(this.size);
    this.pos = 0;
  }

  process(inputs) {
    const channels = inputs[0];
    if (!channels || channels.length === 0 || !channels[0]) return true;
    const frames = channels[0].length;
    const nch = channels.length;

    for (let i = 0; i < frames; i++) {
      let v = 0;
      for (let c = 0; c < nch; c++) v += channels[c][i] || 0;
      this.buf[this.pos++] = v / nch;
      if (this.pos === this.size) {
        const out = this.buf;
        this.buf = new Float32Array(this.size);
        this.pos = 0;
        this.port.postMessage(out, [out.buffer]);
      }
    }
    return true;
  }
}

registerProcessor('pcm-capture', PCMCapture);
