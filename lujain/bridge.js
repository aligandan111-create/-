/*
 * Lujain native bridge — runs FIRST inside lujain.html (before the app code).
 * Android WebView has no working Web Speech API, so this rebuilds:
 *   window.SpeechRecognition  on top of the native "LujainSpeech" interface
 *   window.speechSynthesis    on top of the native "LujainTTS" interface
 * Works with both native APIs:
 *   A) LujainSpeech.startListen(id, lang, interim, continuous) / stopListen(id)
 *      -> window.__lujainSpeechEvent(id, type, payloadJson)  type: start|partial|final|error|end
 *   B) LujainSpeech.start(lang, continuous, interim) / stop() / abort()
 *      -> window.__lujainSR(type, text, isFinal)
 */
(function () {
  window.LUJAIN_APP = !!(window.LujainSpeech || window.LujainTTS || window.LujainNative);

  /* ---------------- speech recognition ---------------- */
  if (window.LujainSpeech) {
    var S = window.LujainSpeech;
    var apiA = typeof S.startListen === 'function';
    var current = null;

    function emit(rec, name, ev) { var h = rec['on' + name]; if (typeof h === 'function') { try { h.call(rec, ev || { type: name }); } catch (e) { console.error(e); } } }
    function pushResult(rec, text, isFinal) {
      if (isFinal) { rec._finals.push(text); rec._interim = ''; } else { rec._interim = text; }
      var results = rec._finals.map(function (t) { var r = [{ transcript: t, confidence: 1 }]; r.isFinal = true; return r; });
      if (rec._interim && rec.interimResults) { var r = [{ transcript: rec._interim, confidence: 0.5 }]; r.isFinal = false; results.push(r); }
      if (isFinal || rec.interimResults) emit(rec, 'result', { resultIndex: isFinal ? rec._finals.length - 1 : rec._finals.length, results: results });
    }
    function finish(rec) { if (current === rec) current = null; emit(rec, 'end'); }

    // API A (your current native code)
    window.__lujainSpeechEvent = function (id, type, payload) {
      var rec = current; if (!rec || rec._id !== id) return;
      var d = {}; try { d = payload ? JSON.parse(payload) : {}; } catch (e) {}
      if (type === 'start') emit(rec, 'start');
      else if (type === 'partial') pushResult(rec, d.text || '', false);
      else if (type === 'final') pushResult(rec, d.text || '', true);
      else if (type === 'error') emit(rec, 'error', { error: d.error || 'aborted' });
      else if (type === 'end') finish(rec);
    };
    // API B (the project I prepared)
    window.__lujainSR = function (type, text, isFinal) {
      var rec = current; if (!rec) return;
      if (type === 'start') emit(rec, 'start');
      else if (type === 'result') pushResult(rec, text, !!isFinal);
      else if (type === 'error') emit(rec, 'error', { error: text });
      else if (type === 'end') finish(rec);
    };

    function NativeSR() { this.lang = 'en-US'; this.continuous = false; this.interimResults = false; this.maxAlternatives = 1; this._finals = []; this._interim = ''; this._id = 'sr_' + Math.random().toString(36).slice(2); }
    NativeSR.prototype.start = function () {
      if (current && current !== this) { try { current.abort(); } catch (e) {} }
      current = this; this._finals = []; this._interim = '';
      try {
        if (apiA) S.startListen(this._id, String(this.lang || 'en-US'), !!this.interimResults, !!this.continuous);
        else S.start(String(this.lang || 'en-US'), !!this.continuous, !!this.interimResults);
      } catch (e) { emit(this, 'error', { error: 'not-allowed' }); finish(this); }
    };
    NativeSR.prototype.stop = function () { try { apiA ? S.stopListen(this._id) : S.stop(); } catch (e) {} };
    NativeSR.prototype.abort = function () {
      try { apiA ? S.stopListen(this._id) : S.abort(); } catch (e) {}
      var self = this; setTimeout(function () { if (current === self) finish(self); }, 300);
    };
    // Always replace: the WebView's own webkitSpeechRecognition exists but does not work.
    window.SpeechRecognition = NativeSR;
    window.webkitSpeechRecognition = NativeSR;
  }

  /* ---------------- text to speech ---------------- */
  if (window.LujainTTS) {
    var pending = {}, seq = 0;
    window.__lujainTTS = function (type, id) {
      var u = pending[id]; if (!u) return;
      try {
        if (type === 'start') { u.onstart && u.onstart({ type: 'start' }); return; }
        delete pending[id];
        if (type === 'end') u.onend && u.onend({ type: 'end' });
        else u.onerror && u.onerror({ type: 'error', error: 'synthesis-failed' });
      } catch (e) { console.error(e); }
    };
    function Utterance(text) { this.text = String(text == null ? '' : text); this.lang = 'en-US'; this.rate = 1; this.pitch = 1; this.volume = 1; this.voice = null; }
    var voices = [
      { name: 'English (United States)', lang: 'en-US', localService: true, default: true, voiceURI: 'en-US' },
      { name: 'العربية', lang: 'ar-SA', localService: true, default: false, voiceURI: 'ar' }
    ];
    var synth = {
      paused: false,
      get speaking() { try { return !!window.LujainTTS.isSpeaking(); } catch (e) { return false; } },
      get pending() { return Object.keys(pending).length > 0; },
      getVoices: function () { return voices; },
      speak: function (u) {
        var id = 'u' + (++seq); pending[id] = u;
        if (!String(u.text).trim() || u.volume === 0) { setTimeout(function () { window.__lujainTTS('end', id); }, 0); return; }
        var lang = (u.voice && u.voice.lang) || u.lang || 'en-US';
        window.LujainTTS.speak(id, String(u.text), lang, Number(u.rate) || 1);
      },
      cancel: function () {
        try { window.LujainTTS.stop(); } catch (e) {}
        Object.keys(pending).forEach(function (id) { var u = pending[id]; delete pending[id]; try { u.onerror && u.onerror({ type: 'error', error: 'interrupted' }); } catch (e) {} });
      },
      pause: function () {}, resume: function () {},
      addEventListener: function () {}, removeEventListener: function () {},
      onvoiceschanged: null
    };
    try { Object.defineProperty(window, 'speechSynthesis', { get: function () { return synth; }, configurable: true }); } catch (e) { window.speechSynthesis = synth; }
    window.SpeechSynthesisUtterance = Utterance;
  }
})();
