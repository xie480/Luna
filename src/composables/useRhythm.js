import { ref } from "vue";
import { gsap } from "gsap";

export function useRhythm() {
  const showSystemAudioListening = ref(false);

  const state = {
    isListening:   false,
    currentStream: null,
    audioContext:  null,
    analyser:      null,
    dataArray:     null,
    source:        null,
    bassLevel:     0,
    midLevel:      0,
    beatDetected:  false,
    beatIntensity: 0,
    bodySway:      0,
    headNod:       0,
    breath:        0.5,
    rafId:         null,
  };

  const RHYTHM_CFG = {
    fftSize:   512,
    smoothing: 0.80,
    bands: {
      bass: { start: 0,  end: 4  },
      mid:  { start: 5,  end: 30 },
    },
    envelope: {
      bassAttack:  0.18,
      bassRelease: 0.04,
      midAttack:   0.12,
      midRelease:  0.06,
    },
    beatDetection: {
      threshold: 0.10,
      decay:     0.75,
      cooldown:  8,
    },
    // 身体只做极轻微左右晃动
    body: {
      maxSway:        3.5,
      smoothness:     0.06,
      sinSpeed:       0.03,
      bassMultiplier: 1.2,
    },
    // 头部：点头为主，轻微左右
    head: {
      nodMaxAngle:   8.0,   // Z轴点头最大角度（小幅）
      swayMaxAngle:  3.0,   // Y轴左右最大角度
      nodSmoothness: 0.14,
      returnSpeed:   0.05,
      microSway:     0.04,
    },
    breath: {
      baseRate:  0.45,
      amplitude: 0.18,
      speed:     0.25,
    },
  };

  function detectBeat(bassLevel, prevBass) {
    const rise  = bassLevel - prevBass;
    const isBeat = rise > RHYTHM_CFG.beatDetection.threshold && bassLevel > 0.06;
    return { isBeat, intensity: Math.min(1.0, rise * 2.0) };
  }

  function startNaturalRhythm(core) {
    if (!state.analyser) {
      console.warn("[律动] analyser 未初始化，无法启动");
      return;
    }

    const { analyser, dataArray } = state;
    const binCount = analyser.frequencyBinCount;

    let bodyPhase    = Math.random() * Math.PI * 2;
    let headSwayPhase = Math.random() * Math.PI * 2;
    let breathPhase  = Math.random() * Math.PI * 2;
    let prevBass     = 0;
    let beatCooldown = 0;

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      analyser.getByteFrequencyData(dataArray);

      // —— 频段能量 ——
      const { bass, mid } = RHYTHM_CFG.bands;
      let bassSum = 0, midSum = 0;

      for (let i = bass.start; i <= bass.end && i < binCount; i++) bassSum += dataArray[i];
      const bassEnergy = bassSum / ((bass.end - bass.start + 1) * 255);

      for (let i = mid.start; i <= mid.end && i < binCount; i++) midSum += dataArray[i];
      const midEnergy = midSum / ((mid.end - mid.start + 1) * 255);

      // —— 包络跟踪 ——
      const { bassAttack, bassRelease, midAttack, midRelease } = RHYTHM_CFG.envelope;
      state.bassLevel += (bassEnergy - state.bassLevel) * (bassEnergy > state.bassLevel ? bassAttack  : bassRelease);
      state.midLevel  += (midEnergy  - state.midLevel)  * (midEnergy  > state.midLevel  ? midAttack   : midRelease);

      // —— 节拍检测 ——
      if (beatCooldown > 0) {
        beatCooldown--;
      } else {
        const beat = detectBeat(state.bassLevel, prevBass);
        if (beat.isBeat) {
          state.beatDetected  = true;
          state.beatIntensity = beat.intensity;
          beatCooldown        = RHYTHM_CFG.beatDetection.cooldown;
        }
      }

      state.beatIntensity *= RHYTHM_CFG.beatDetection.decay;
      if (state.beatIntensity < 0.02) state.beatDetected = false;

      const cfg = RHYTHM_CFG;

      // —— 身体极轻微左右晃动（X轴）——
      const targetBodyX = Math.sin(bodyPhase) * (1 + state.bassLevel * cfg.body.bassMultiplier) * cfg.body.maxSway * 0.5;

      // —— 头部点头（Z轴）：节拍时轻点，无节拍时微幅随中频起伏 ——
      let targetHeadZ;
      if (state.beatDetected) {
        // 节拍触发时轻柔点头，幅度受 beatIntensity 控制
        targetHeadZ = state.beatIntensity * cfg.head.nodMaxAngle;
      } else {
        // 无节拍时极轻微随音乐能量微动
        targetHeadZ = Math.sin(headSwayPhase * 0.7) * state.midLevel * cfg.head.nodMaxAngle * 0.35;
      }

      // —— 头部轻微左右（Y轴），仅随身体极小幅联动 ——
      const targetHeadY = Math.sin(headSwayPhase) * state.bassLevel * cfg.head.swayMaxAngle * 0.4;

      // —— 呼吸 ——
      const targetBreath = cfg.breath.baseRate + Math.sin(breathPhase) * cfg.breath.amplitude * (1 + state.midLevel * 0.5);

      // —— 平滑过渡（lerp）——
      const headSmooth = state.beatDetected ? cfg.head.nodSmoothness : cfg.head.returnSpeed;

      state.bodySway += (targetBodyX  - state.bodySway) * cfg.body.smoothness;
      state.headNod  += (targetHeadZ  - state.headNod)  * headSmooth;
      state.breath   += (targetBreath - state.breath)   * 0.07;

      // —— 推进相位（缓慢，保持自然感）——
      bodyPhase     = (bodyPhase     + cfg.body.sinSpeed * (1 + state.bassLevel * 0.6)) % (Math.PI * 2);
      headSwayPhase = (headSwayPhase + cfg.head.microSway * (1 + state.midLevel  * 0.4)) % (Math.PI * 2);
      breathPhase   = (breathPhase   + cfg.breath.speed * 0.04)                          % (Math.PI * 2);

      // —— 写入模型参数 ——
      try {
        core.setParameterValueById("ParamBodyAngleX", state.bodySway);
        core.setParameterValueById("ParamAngleZ",     state.headNod);
        core.setParameterValueById("ParamAngleY",     targetHeadY);
        core.setParameterValueById("ParamBreath",     state.breath);
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
      headZ:  core.getParameterValueById("ParamAngleZ")     || 0,
      headY:  core.getParameterValueById("ParamAngleY")     || 0,
      breath: core.getParameterValueById("ParamBreath")     || 0.5,
    };

    gsap.to(resetState, {
      duration: 1.2,
      bodyX:    0,
      headZ:    0,
      headY:    0,
      breath:   0.5,
      ease:     "power2.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", resetState.bodyX);
          core.setParameterValueById("ParamAngleZ",     resetState.headZ);
          core.setParameterValueById("ParamAngleY",     resetState.headY);
          core.setParameterValueById("ParamBreath",     resetState.breath);
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
      analyser.fftSize               = RHYTHM_CFG.fftSize;
      analyser.smoothingTimeConstant = RHYTHM_CFG.smoothing;
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
