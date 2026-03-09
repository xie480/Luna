import { ref } from "vue";
import { gsap } from "gsap";

/**
 * 音频律动 composable
 * 负责系统音频捕获、节奏分析、Live2D 模型律动驱动
 */
export function useRhythm() {
  const showSystemAudioListening = ref(false);

  const state = {
    isListening: false,
    currentStream: null,
    audioContext: null,
    analyser: null,
    dataArray: null,
    source: null,
    bassLevel: 0,
    midLevel: 0,
    beatDetected: false,
    beatIntensity: 0,
    bodySway: 0,
    headNod: 0,
    breath: 0.5,
    rafId: null,
  };

  const RHYTHM_CFG = {
    fftSize: 512,
    smoothing: 0.75,
    bands: {
      bass:   { start: 0,  end: 4  },
      lowMid: { start: 5,  end: 18 },
      mid:    { start: 19, end: 55 },
    },
    envelope: {
      bassAttack:  0.22,
      bassRelease: 0.06,
      midAttack:   0.15,
      midRelease:  0.09,
    },
    beatDetection: {
      threshold: 0.12,
      decay:     0.80,
      cooldown:  6,
    },
    body: {
      // 更大的摆动幅度
      maxSway:      14.0,
      smoothness:   0.10,
      leanFactor:   0.55,
      sinSpeed:     0.06,
      bassMultiplier: 3.5,
    },
    head: {
      // 点头幅度明显增大
      nodIntensity:  1200,
      microNodScale: 4.5,
      nodSmoothness: 0.28,
      returnSpeed:   0.04,
    },
    eye: {
      // 眼部随律动轻微晃动
      maxBallX: 0.35,
      smooth:   0.12,
    },
    breath: {
      baseRate:  0.42,
      amplitude: 0.22,
      speed:     0.28,
    },
    mouth: {
      enabled:     true,
      sensitivity: 0.65,
      minOpen:     0.05,
      maxOpen:     100,
    },
  };

  /** 节拍检测 */
  function detectBeat(bassLevel, prevBass) {
    const rise = bassLevel - prevBass;
    const isBeat = rise > RHYTHM_CFG.beatDetection.threshold && bassLevel > 0.08;
    return { isBeat, intensity: Math.min(1.0, rise * 2.5) };
  }

  /**
   * 启动自然律动主循环
   * @param {object} core - Live2D coreModel 实例
   */
  function startNaturalRhythm(core) {
    // 修复：原代码错误地使用了字符串 `!state.analyser`
    if (!state.analyser) {
      console.warn("[律动] analyser 未初始化，无法启动");
      return;
    }

    const { analyser, dataArray } = state;
    const binCount = analyser.frequencyBinCount;

    let bodyPhase  = Math.random() * Math.PI * 2;
    let headPhase  = Math.random() * Math.PI * 2;
    let breathPhase = Math.random() * Math.PI * 2;
    let eyePhase   = Math.random() * Math.PI * 2;
    let prevBass   = 0;
    let beatCooldown = 0;

    // 平滑后的眼部偏移
    let smoothEyeX = 0;

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      analyser.getByteFrequencyData(dataArray);

      // —— 频段能量计算 ——
      const { bass, lowMid, mid } = RHYTHM_CFG.bands;
      let bassSum = 0, midSum = 0;

      for (let i = bass.start; i <= bass.end && i < binCount; i++) bassSum += dataArray[i];
      const bassEnergy = bassSum / ((bass.end - bass.start + 1) * 255);

      for (let i = lowMid.start; i <= mid.end && i < binCount; i++) midSum += dataArray[i];
      const midEnergy = midSum / ((mid.end - lowMid.start + 1) * 255);

      // —— 包络跟踪 ——
      const bAtk = RHYTHM_CFG.envelope.bassAttack;
      const bRel = RHYTHM_CFG.envelope.bassRelease;
      const mAtk = RHYTHM_CFG.envelope.midAttack;
      const mRel = RHYTHM_CFG.envelope.midRelease;

      state.bassLevel += (bassEnergy - state.bassLevel) * (bassEnergy > state.bassLevel ? bAtk : bRel);
      state.midLevel  += (midEnergy  - state.midLevel)  * (midEnergy  > state.midLevel  ? mAtk : mRel);

      // —— 节拍检测 ——
      if (beatCooldown > 0) {
        beatCooldown--;
      } else {
        const beat = detectBeat(state.bassLevel, prevBass);
        if (beat.isBeat) {
          state.beatDetected  = true;
          state.beatIntensity = beat.intensity;
          beatCooldown = RHYTHM_CFG.beatDetection.cooldown;
          // 节拍时随机扰动相位，增加自然感
          bodyPhase  += (Math.random() - 0.5) * 0.8;
          headPhase  += (Math.random() - 0.5) * 0.5;
        }
      }

      // 节拍强度衰减
      state.beatIntensity *= RHYTHM_CFG.beatDetection.decay;
      if (state.beatIntensity < 0.03) state.beatDetected = false;

      const cfg = RHYTHM_CFG;

      // —— 身体摇摆 ——
      // 基础正弦波 + 低频能量调制幅度 + 节拍冲击
      const baseSway = Math.sin(bodyPhase) * (1 + state.bassLevel * cfg.body.bassMultiplier) * cfg.body.maxSway * 0.5;
      const beatSway = state.beatIntensity * cfg.body.maxSway * Math.sin(bodyPhase * 2.7) * 0.55;
      const targetBodyX = baseSway + beatSway;

      // 身体前后倾（Z轴），随中频起伏
      const targetBodyZ = Math.sin(bodyPhase * 1.3 + 0.8) * state.midLevel * cfg.body.leanFactor * 8;

      // —— 头部点头 ——
      let targetHeadZ;
      if (state.beatDetected) {
        // 继续输出 `useRhythm.js` 断点处之后的内容：

src\composables\useRhythm.js
