import { ref } from "vue";
import { gsap } from "gsap";

export function useRhythm() {
  const showSystemAudioListening = ref(false);

  const state = {
    isListening:    false,
    currentStream:  null,
    audioContext:   null,
    analyser:       null,
    dataArray:      null,
    source:         null,
    bassLevel:      0,
    midLevel:       0,
    beatDetected:   false,
    beatIntensity:  0,
    bodySway:       0,
    headNod:        0,
    breath:         0.5,
    rafId:          null,
  };
                                                                                                                                                                                                      
  const RHYTHM_CFG = {
    fftSize:   512,
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
      maxSway:        14.0,
      smoothness:     0.10,
      leanFactor:     0.55,
      sinSpeed:       0.06,
      bassMultiplier: 3.5,
    },
    head: {
      nodIntensity:  1200,
      microNodScale: 4.5,
      nodSmoothness: 0.28,
      returnSpeed:   0.04,
    },
    eye: {
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

  function detectBeat(bassLevel, prevBass) {
    const rise  = bassLevel - prevBass;
    const isBeat = rise > RHYTHM_CFG.beatDetection.threshold && bassLevel > 0.08;
    return { isBeat, intensity: Math.min(1.0, rise * 2.5) };
  }

  function startNaturalRhythm(core) {
    if (!state.analyser) {
      console.warn("[律动] analyser 未初始化，无法启动");
      return;
    }

    const { analyser, dataArray } = state;
    const binCount = analyser.frequencyBinCount;

    let bodyPhase    = Math.random() * Math.PI * 2;
    let headPhase    = Math.random() * Math.PI * 2;
    let breathPhase  = Math.random() * Math.PI * 2;
    let eyePhase     = Math.random() * Math.PI * 2;
    let prevBass     = 0;
    let beatCooldown = 0;
    let smoothEyeX   = 0;

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      analyser.getByteFrequencyData(dataArray);

      // —— 频段能量 ——
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
          beatCooldown        = RHYTHM_CFG.beatDetection.cooldown;
          bodyPhase += (Math.random() - 0.5) * 0.8;
          headPhase += (Math.random() - 0.5) * 0.5;
        }
      }

      state.beatIntensity *= RHYTHM_CFG.beatDetection.decay;
      if (state.beatIntensity < 0.03) state.beatDetected = false;

      const cfg = RHYTHM_CFG;

      // —— 身体摇摆（X轴）——
      const baseSway    = Math.sin(bodyPhase) * (1 + state.bassLevel * cfg.body.bassMultiplier) * cfg.body.maxSway * 0.5;
      const beatSway    = state.beatIntensity * cfg.body.maxSway * Math.sin(bodyPhase * 2.7) * 0.55;
      const targetBodyX = baseSway + beatSway;

      // 身体前后倾（Z轴）
      const targetBodyZ = Math.sin(bodyPhase * 1.3 + 0.8) * state.midLevel * cfg.body.leanFactor * 8;

      // —— 头部点头（Z轴）——
      let targetHeadZ;
      if (state.beatDetected) {
        targetHeadZ = -state.beatIntensity * cfg.head.nodIntensity;
      } else {
        targetHeadZ = Math.sin(headPhase * 1.7) * state.midLevel * cfg.head.microNodScale * 18;
      }

      // 头部左右（Y轴）
      const targetHeadY = Math.sin(bodyPhase * 0.8 + 0.4) * state.bassLevel * 12;

      // —— 呼吸 ——
      const targetBreath = cfg.breath.baseRate + Math.sin(breathPhase) * cfg.breath.amplitude * (1 + state.midLevel * 0.8);

      // —— 眼球漂移 ——
      const targetEyeX = Math.sin(eyePhase) * state.bassLevel * cfg.eye.maxBallX;
      smoothEyeX += (targetEyeX - smoothEyeX) * cfg.eye.smooth;

      // —— 平滑过渡（lerp）——
      const bodySmooth = cfg.body.smoothness;
      const headSmooth = state.beatDetected ? cfg.head.nodSmoothness : cfg.head.returnSpeed;

      state.bodySway += (targetBodyX  - state.bodySway) * bodySmooth;
      state.headNod  += (targetHeadZ  - state.headNod)  * headSmooth;
      state.breath   += (targetBreath - state.breath)   * 0.08;

      // —— 推进相位 ——
      bodyPhase   = (bodyPhase   + cfg.body.sinSpeed * (1 + state.bassLevel * 1.2)) % (Math.PI * 2);
      headPhase   = (headPhase   + 0.035 * (1 + state.midLevel  * 0.6))             % (Math.PI * 2);
      breathPhase = (breathPhase + cfg.breath.speed * 0.045)                        % (Math.PI * 2);
      eyePhase    = (eyePhase    + 0.028 * (1 + state.bassLevel * 0.5))             % (Math.PI * 2);

      // —— 写入模型参数 ——
      try {
        core.setParameterValueById("ParamBodyAngleX",  state.bodySway);
        core.setParameterValueById("ParamBodyAngleZ",  targetBodyZ);
        core.setParameterValueById("ParamAngleZ",      state.headNod);
        core.setParameterValueById("ParamAngleY",      targetHeadY);
        core.setParameterValueById("ParamBreath",      state.breath);
        core.setParameterValueById("ParamEyeBallX",    smoothEyeX);

        if (cfg.mouth.enabled) {
          const mouthOpen = cfg.mouth.minOpen + state.midLevel * cfg.mouth.sensitivity;
          core.setParameterValueById("ParamMouthOpenY", Math.min(cfg.mouth.maxOpen, mouthOpen));
        }
      } catch {}

      prevBass    = state.bassLevel;
      state.rafId = requestAnimationFrame(frame);
    }

    state.rafId = requestAnimationFrame(frame);
    console.log("[律动] 自然律动循环启动");
  }
                                                                                                                                                                                                      
  function stopRhythmLoop() {
    if (state.rafId) {
      cancelAnimationFrame(state.rafId);
      state.rafId = null;
    }
  }

  function smoothReset(core) {
    if (!core) return;

    const resetState = {
      bodyX:  core.getParameterValueById("ParamBodyAngleX") || 0,
      bodyZ:  core.getParameterValueById("ParamBodyAngleZ") || 0,
      headZ:  core.getParameterValueById("ParamAngleZ")     || 0,
      headY:  core.getParameterValueById("ParamAngleY")     || 0,
      breath: core.getParameterValueById("ParamBreath")     || 0.5,
      mouth:  core.getParameterValueById("ParamMouthOpenY") || 0,
      eyeX:   core.getParameterValueById("ParamEyeBallX")   || 0,
    };

    gsap.to(resetState, {
      duration: 1.0,
      bodyX:    0,
      bodyZ:    0,
      headZ:    0,
      headY:    0,
      breath:   0.5,
      mouth:    0,
      eyeX:     0,
      ease:     "power2.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX",  resetState.bodyX);
          core.setParameterValueById("ParamBodyAngleZ",  resetState.bodyZ);
          core.setParameterValueById("ParamAngleZ",      resetState.headZ);
          core.setParameterValueById("ParamAngleY",      resetState.headY);
          core.setParameterValueById("ParamBreath",      resetState.breath);
          core.setParameterValueById("ParamMouthOpenY",  resetState.mouth);
          core.setParameterValueById("ParamEyeBallX",    resetState.eyeX);
        } catch {}
      },
      onComplete() {
        console.log("[律动] 模型已平滑重置");
      },
    });
  }

  async function toggleSystemAudio(core, trackingEnabled) {
    if (showSystemAudioListening.value) {
      stopSystemAudioListening(core, trackingEnabled);
      return;
    }

    if (trackingEnabled.value) trackingEnabled.value = false;
                                                                                                                                                                                                      
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { mandatory: { chromeMediaSource: "desktop" } },
        video: { mandatory: { chromeMediaSource: "desktop" } },
      });
                                                                                                                                                                                                      
      state.currentStream = stream;

      if (!state.audioContext) {
        state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
      }

      const analyser = state.audioContext.createAnalyser();
      analyser.fftSize                  = RHYTHM_CFG.fftSize;
      analyser.smoothingTimeConstant    = RHYTHM_CFG.smoothing;
      state.analyser  = analyser;
      state.dataArray = new Uint8Array(analyser.frequencyBinCount);
                                                                                                                                                                                                      
      state.source = state.audioContext.createMediaStreamSource(stream);
      state.source.connect(analyser);

      state.isListening              = true;
      showSystemAudioListening.value = true;

      startNaturalRhythm(core);
      console.log("[律动] 已连接到系统音频");
    } catch (err) {
      console.error("[律动] 音频初始化失败:", err);
    }
  }

  function stopSystemAudioListening(core, trackingEnabled) {
    if (!trackingEnabled.value) trackingEnabled.value = true;

    state.isListening              = false;
    showSystemAudioListening.value = false;

    stopRhythmLoop();
    smoothReset(core);

    if (state.currentStream) {
      state.currentStream.getTracks().forEach((t) => t.stop());
      state.currentStream = null;
    }

    if (state.source) {
      try { state.source.disconnect(); } catch {}
      state.source = null;
    }

    if (state.audioContext) {
      state.audioContext.close().catch(() => {});
      state.audioContext = null;
      state.analyser     = null;
    }

    console.log("[律动] 已断开音频连接");
  }

  function dispose(core, trackingEnabled) {
    if (state.isListening) {
      stopSystemAudioListening(core, trackingEnabled);
    } else {
      stopRhythmLoop();
    }
  }
                                                                                                                                                                                                      
  return {
    showSystemAudioListening,
    toggleSystemAudio,
    dispose,
  };
}                                                             