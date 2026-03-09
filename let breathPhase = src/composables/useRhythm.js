import { ref } from "vue";
import { gsap } from "gsap";

/**
 * 音頻律動 composable
 * 負責系統音頻捕獲、節奏分析、Live2D 模型律動驅動
 * 優化點：大幅提升律動幅度、增加自然身體旋轉、更靈敏節拍響應
 */
export function useRhythm() {
  const showSystemAudioListening = ref(false);

  // 內部狀態（非響應式，性能優先）
  const state = {
    isListening: false,
    currentStream: null,
    audioContext: null,
    analyser: null,
    dataArray: null,
    source: null,
    bassLevel: 0,
    midLevel: 0,
    highLevel: 0,
    bodySway: 0,
    bodyZ: 0,
    headNod: 0,
    headY: 0,
    breath: 0.5,
    beatDetected: false,
    beatIntensity: 0,
    rafId: null,
  };

  // 律動配置 —— 大幅提升幅度讓律動更明顯自然
  const CFG = {
    fftSize: 512,
    smoothing: 0.75,
    bands: {
      bass:   { start: 1,  end: 4  },
      lowMid: { start: 5,  end: 14 },
      mid:    { start: 15, end: 45 },
      high:   { start: 46, end: 90 },
    },
    envelope: {
      bassAttack:  0.35,
      bassRelease: 0.08,
      midAttack:   0.25,
      midRelease:  0.1,
      highAttack:  0.3,
      highRelease: 0.12,
    },
    beat: {
      threshold: 0.12,
      minBass:   0.08,
      decay:     0.82,
      cooldown:  6,
    },
    body: {
      maxSway:    18.0,   // 身體左右最大擺動角度（原 6，提升 3 倍）
      maxLean:    8.0,    // 身體前後傾斜
      smoothness: 0.12,
    },
    head: {
      maxNod:       30,
      maxTurn:      12,
      nodSmooth:    0.28,
      returnSmooth: 0.06,
    },
    breath: {
      baseRate:  0.38,
      amplitude: 0.22,
      speed:     0.28,
    },
    mouth: {
      enabled:     true,
      sensitivity: 0.55,
      min:         0.0,
      max:         1.0,
    },
  };

  /** 計算某頻段的平均能量（0-1） */
  function bandEnergy(dataArray, start, end, binCount) {
    let sum = 0;
    const len = Math.min(end, binCount - 1) - start + 1;
    if (len <= 0) return 0;
    for (let i = start; i <= Math.min(end, binCount - 1); i++) sum += dataArray[i];
    return sum / (len * 255);
  }

  /** 平滑包絡跟蹤（attack/release 非對稱） */
  function envelope(current, target, attack, release) {
    return current + (target > current
      ? (target - current) * attack
      : (target - current) * release);
  }

  /**
   * 啟動自然律動主循環
   * @param {object} core - Live2D coreModel 實例
   */
  function startNaturalRhythm(core) {
    if (!state.analyser) {
      console.warn("[律動] analyser 未初始化，無法啟動");
      return;
    }

    const { analyser, dataArray } = state;
    const binCount = analyser.frequencyBinCount;

    // 各正弦波相位，加入隨機初相讓每次律動都略有不同
    let bodyPhase   = Math.random() * Math.PI * 2;
    let headPhase   = Math.random() * Math.PI * 2;
    let breathPhase = Math.random() * Math.PI * 2;
    let prevBass    = 0;
    let beatCooldown = 0;

    function frame() {
      if (!state.isListening) {
        state.rafId = null;
        return;
      }

      // 讀取頻譜數據
      analyser.getByteFrequencyData(dataArray);

      // 計算各頻段原始能量
      const rawBass = bandEnergy(dataArray, CFG.bands.bass.start,   CFG.bands.bass.end,   binCount);
      const rawMid  = bandEnergy(dataArray, CFG.bands.lowMid.start, CFG.bands.mid.end,    binCount);
      const rawHigh = bandEnergy(dataArray, CFG.bands.high.start,   CFG.bands.high.end,   binCount);

      // 包絡平滑（快速上升，慢速衰減）
      state.bassLevel = envelope(state.bassLevel, rawBass, CFG.envelope.bassAttack,  CFG.envelope.bassRelease);
      state.midLevel  = envelope(state.midLevel,  rawMid,  CFG.envelope.midAttack,   CFG.envelope.midRelease);
      state.highLevel = envelope(state.highLevel, rawHigh, CFG.envelope.highAttack,  CFG.envelope.highRelease);

      // 節拍檢測：低頻能量突然上升超過閾值
      if (beatCooldown > 0) {
        beatCooldown--;
        state.beatIntensity *= CFG.beat.decay;
      } else {
        const rise = state.bassLevel - prevBass;
        if (rise > CFG.beat.threshold && state.bassLevel > CFG.beat.minBass) {
          state.beatDetected  = true;
          state.beatIntensity = Math.min(1.0, rise * 3.5);
          beatCooldown        = CFG.beat.cooldown;
          // 節拍觸發時，給相位注入隨機擾動，避免律動週期感太強
          bodyPhase += (Math.random() - 0.5) * 1.2;
          headPhase += (Math.random() - 0.5) * 0.8;
        } else {
          state.beatIntensity *= CFG.beat.decay;
        }
      }
      if (state.beatIntensity < 0.04) state.beatDetected = false;

      // ---- 計算各目標值 ----

      // 身體左右搖擺：低頻驅動 + 節拍衝擊
      const baseSway  = Math.sin(bodyPhase) * state.bassLevel * CFG.body.maxSway;
      const beatSway  = state.beatDetected
        ? Math.sin(bodyPhase * 2.3) * state.beatIntensity * CFG.body.maxSway * 0.7
        : 0;
      const targetBodyX = baseSway + beatSway;

      // 身體前後傾斜：中頻驅動
      const targetBodyZ = Math.sin(bodyPhase * 0.6 + 1.2) * state.midLevel * CFG.body.maxLean;

      // 頭部點頭：節拍時下點，平時隨中頻微動
      const targetHeadZ = state.beatDetected
        ? -state.beatIntensity * CFG.head.maxNod
        : Math.sin(headPhase * 1.8) * state.midLevel * CFG.head.maxNod * 0.25;

      // 頭部左右轉：跟隨身體但幅度更小，增加自然感
      const targetHeadY = Math.sin(bodyPhase * 0.8 + 0.5) * state.bassLevel * CFG.head.maxTurn;

      // 呼吸：基礎正弦 + 音量調製
      const targetBreath = CFG.breath.baseRate
        + Math.sin(breathPhase) * CFG.breath.amplitude
        + state.bassLevel * 0.1;

      // ---- 平滑插值（lerp） ----
      const headSmooth = state.beatDetected ? CFG.head.nodSmooth : CFG.head.returnSmooth;

      state.bodySway += (targetBodyX - state.bodySway) * CFG.body.smoothness;
      state.bodyZ    += (targetBodyZ - state.bodyZ)    * CFG.body.smoothness;
      state.headNod  += (targetHeadZ - state.headNod)  * headSmooth;
      state.headY    += (targetHeadY - state.headY)    * CFG.head.returnSmooth;
      state.breath   += (targetBreath - state.breath)  * 0.08;

      // ---- 推進相位 ----
      // 相位速度與低頻能量正相關，音量越大律動越快
      const bpmFactor = 1 + state.bassLevel * 2.5;
      bodyPhase   = (bodyPhase   + 0.04 * bpmFactor) % (Math.PI * 2);
      headPhase   = (headPhase   + 0.03 * bpmFactor) % (Math.PI * 2);
      breathPhase = (breathPhase + CFG.breath.speed * 0.04) % (Math.PI * 2);

      // ---- 寫入模型參數 ----
      try {
        core.setParameterValueById("ParamBodyAngleX", state.bodySway);
        core.setParameterValueById("ParamBodyAngleZ", state.bodyZ);
        core.setParameterValueById("ParamAngleZ",     state.headNod);
        core.setParameterValueById("ParamAngleY",     state.headY);
        core.setParameterValueById("ParamBreath",     Math.max(0, Math.min(1, state.breath)));

        if (CFG.mouth.enabled) {
          // 嘴型：中頻驅動，節拍時張大
          const mouthVal = CFG.mouth.min
            + state.midLevel * CFG.mouth.sensitivity
            + (state.beatDetected ? state.beatIntensity * 0.3 : 0);
          core.setParameterValueById("ParamMouthOpenY",
            Math.max(CFG.mouth.min, Math.min(CFG.mouth.max, mouthVal)));
        }
      } catch {}

      prevBass    = state.bassLevel;
      state.rafId = requestAnimationFrame(frame);
    }

    state.rafId = requestAnimationFrame(frame);
    console.log("[律動] 自然律動循環啟動");
  }

  /** 停止律動循環並取消 RAF */
  function stopRhythmLoop() {
    if (state.rafId) {
      cancelAnimationFrame(state.rafId);
      state.rafId = null;
    }
  }

  /**
   * 平滑重置模型參數到初始狀態
   * @param {object} core - Live2D coreModel 實例
   */
  function smoothReset(core) {
    if (!core) return;

    const resetState = {
      bodyX:  core.getParameterValueById("ParamBodyAngleX") || 0,
      bodyZ:  core.getParameterValueById("ParamBodyAngleZ") || 0,
      headZ:  core.getParameterValueById("ParamAngleZ")     || 0,
      headY:  core.getParameterValueById("ParamAngleY")     || 0,
      breath: core.getParameterValueById("ParamBreath")     || 0.5,
      mouth:  core.getParameterValueById("ParamMouthOpenY") || 0,
    };

    gsap.to(resetState, {
      duration: 1.0,
      bodyX:  0,
      bodyZ:  0,
      headZ:  0,
      headY:  0,
      breath: 0.5,
      mouth:  0,
      ease: "power2.out",
      onUpdate() {
        try {
          core.setParameterValueById("ParamBodyAngleX", resetState.bodyX);
          core.setParameterValueById("ParamBodyAngleZ", resetState.bodyZ);
          core.setParameterValueById("ParamAngleZ",     resetState.headZ);
          core.setParameterValueById("ParamAngleY",     resetState.headY);
          core.setParameterValueById("ParamBreath",     resetState.breath);
          core.setParameterValueById("ParamMouthOpenY", resetState.mouth);
        } catch {}
      },
      onComplete() {
        console.log("[律動] 模型參數已平滑重置");
      },
    });
  }

  /**
   * 切換系統音頻監聽
   * @param {object} core - Live2D coreModel 實例
   * @param {object} trackingEnabled - ref，視線追蹤開關
   */
  async function toggleSystemAudio(core, trackingEnabled) {
    if (showSystemAudioListening.value) {
      stopSystemAudioListening(core, trackingEnabled);
      return;
    }

    // 開啟律動時關閉視線追蹤，避免參數衝突
    if (trackingEnabled.value) trackingEnabled.value = false;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { mandatory: { chromeMediaSource: "desktop" } },
        video: { mandatory: { chromeMediaSource: "desktop" } },
      });

      state.currentStream = stream;

      // 複用已有 AudioContext，避免重複創建
      if (!state.audioContext) {
        state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
      }

      const analyser = state.audioContext.createAnalyser();
      analyser.fftSize                = CFG.fftSize;
      analyser.smoothingTimeConstant  = CFG.smoothing;
      state.analyser  = analyser;
      state.dataArray = new Uint8Array(analyser.frequencyBinCount);

      state.source = state.audioContext.createMediaStreamSource(stream);
      state.source.connect(analyser);

      // 重置運動狀態，避免從舊值開始插值跳變
      state.bassLevel = state.midLevel = state.highLevel = 0;
      state.bodySway  = state.bodyZ = state.headNod = state.headY = 0;
      state.beatIntensity = 0;
      state.beatDetected  = false;

      state.isListening           = true;
      showSystemAudioListening.value = true;

      startNaturalRhythm(core);
      console.log("[律動] 已連接到系統音頻");
    } catch (err) {
      console.error("[律動] 音頻初始化失敗:", err);
    }
  }

  /**
   * 停止系統音頻監聽並釋放所有資源
   * @param {object} core - Live2D coreModel 實例
   * @param {object} trackingEnabled - ref，視線追蹤開關
   */
  function stopSystemAudioListening(core, trackingEnabled) {
    // 恢復視線追蹤
    if (!trackingEnabled.value) trackingEnabled.value = true;

    state.isListening              = false;
    showSystemAudioListening.value = false;

    stopRhythmLoop();
    smoothReset(core);

    // 釋放媒體流
    if (state.currentStream) {
      state.currentStream.getTracks().forEach((t) =>