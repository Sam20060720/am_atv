// Production inline bootstrap, fake WebAudio only. No network, microphone, device mix or real media.
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const kotlin=fs.readFileSync('app/src/main/java/dev/amatv/integration/MusicKitStationPlayer.kt','utf8');
const script=kotlin.split('</head><body><script>')[1].split('</script><script')[0]
  .replace('$configuration',JSON.stringify({epoch:7,station:'fixture',developer:'fixture',user:'fixture',build:'test',position:0,spectrumActive:false,storefront:'tw'}))
  .replaceAll('$BRIDGE','bridge');
const flush=async()=>{for(let i=0;i<12;i++)await Promise.resolve();};
function fixture(diagnostics=false, elementProbe=false){
  const events={}, spectra=[], reports=[], contexts=[], intervals=new Map(), timeouts=new Map();
  let nextTimer=1, originalStops=0, mode='signal', captureCalls=0, forcedCaptureError=false, resumeGate=null, suspendGate=null, elementSourceCreates=0;
  const boundElements=new WeakMap();
  const originalTrack={stop:()=>originalStops++};
  const elementEvents={};
  const element={paused:false,readyState:4,mediaKeys:null,currentSrc:elementProbe ? 'blob:https://amatv.invalid/fixture' : 'https://cdn.invalid/fixture',originalTrack,
    addEventListener:(name,callback)=>{const previous=elementEvents[name];elementEvents[name]=previous ? ()=>{previous();callback();} : callback;},
    captureStream(){
      captureCalls++;
      if(forcedCaptureError){const e=new Error('blocked');e.name='SecurityError';throw e;}
      const track={readyState:'live',muted:false,enabled:true,stops:0,stop(){this.stops++;this.readyState='ended';}};
      return {track,getAudioTracks:()=>[track],getTracks:()=>[track]};
    }};
  function AudioContext(){
    this.state='suspended';this.sampleRate=48000;this.closed=0;this.resumes=0;this.suspends=0;
    const destination={kind:'destination'};
    Object.defineProperty(this,'destination',{get(){if(elementProbe)return destination;throw new Error('Analysis must never connect to destination');}});
    this.createMediaElementSource=mediaElement=>{
      assert.ok(!boundElements.has(mediaElement),'An element must never bind a second MediaElementSource');
      boundElements.set(mediaElement,this);elementSourceCreates++;this.element=mediaElement;
      return this.source={connected:null,connect(target){this.connected=target;},disconnect(){this.connected=null;}};
    };
    this.createMediaStreamSource=stream=>{
      this.stream=stream;
      return this.source={connected:null,connect(target){this.connected=target;},disconnect(){this.connected=null;}};
    };
    this.createAnalyser=()=>this.analyser={fftSize:1024,frequencyBinCount:512,smoothingTimeConstant:0,
      getFloatFrequencyData(array){
        if(mode==='sample_error')throw new Error('Synthetic first-sample failure');
        array.fill(mode==='silence' ? -Infinity : -95);
        if(mode==='signal'){array[2]=-12;array[18]=-20;array[90]=-30;}
      },
      getFloatTimeDomainData(array){for(let i=0;i<array.length;i++)array[i]=mode==='silence' ? 0 : Math.sin(i*.2)*.2;},
      connected:null,connect(target){this.connected=target;},disconnect(){this.connected=null;}};
    this.resume=()=>{this.resumes++;return resumeGate ? resumeGate.then(()=>{this.state='running';}) : (this.state='running',Promise.resolve());};
    this.suspend=()=>{this.suspends++;return suspendGate ? suspendGate.then(()=>{this.state='suspended';}) : (this.state='suspended',Promise.resolve());};
    this.close=()=>{this.closed++;this.state='closed';return Promise.resolve();};
    contexts.push(this);
  }
  const music={nowPlayingItem:{attributes:{name:'Station'},isLiveRadioStation:true},playbackState:2,
    storefrontCountryCode:'tw',me:()=>Promise.resolve(),
    currentPlaybackDuration:0,currentPlaybackTime:10,queue:{length:1,nextPlayableItemIndex:-1,previousPlayableItemIndex:-1},
    addEventListener:(name,callback)=>{events[name]=callback;},setQueue:()=>Promise.resolve(),
    play:()=>Promise.resolve(),pause:()=>Promise.resolve(),stop:()=>Promise.resolve()};
  const window={AudioContext,setInterval:(callback,ms)=>{const id=nextTimer++;intervals.set(id,{callback,ms});return id;},
    clearInterval:id=>intervals.delete(id),setTimeout:(callback,ms)=>{const id=nextTimer++;timeouts.set(id,{callback,ms});return id;},
    clearTimeout:id=>timeouts.delete(id),addEventListener:(name,callback)=>{events['window:'+name]=callback;}};
  const context={console,window,document:{addEventListener:(name,callback)=>{events[name]=callback;},getElementById:()=>element},
    bridge:{report:raw=>reports.push(JSON.parse(raw)),spectrum:raw=>{
      assert.ok(raw.length<=512);const data=JSON.parse(raw);
      const fields=['epoch','status','captured','available','rate','bass','mid','treble'];
      if(diagnostics)fields.push('reason','captureApi','contextApi','mediaKeys','contextState','errorName','sameOriginBlob','graphMode');
      assert.deepEqual(Object.keys(data).sort(),fields.sort());
      assert.equal(data.epoch,7);for(const name of ['bass','mid','treble'])assert.ok(Number.isFinite(data[name])&&data[name]>=0&&data[name]<=1);
      spectra.push(data);
    }},
    MusicKit:{PlaybackStates:{playing:2,stopped:4,loading:1,waiting:6,stalled:7,seeking:8,completed:9},configure:()=>Promise.resolve(music)},
    AbortController,fetch:()=>{throw new Error('No network in spectrum fixture');}};
  context.URL=URL;context.location={origin:'https://amatv.invalid'};
  let boot=script;
  if(diagnostics)boot=boot.replace('"spectrumActive":false','"spectrumActive":false,"metadataDiagnostics":true');
  vm.runInNewContext(boot,context);
  return {events,spectra,reports,contexts,music,window,element,elementEvents,intervals,timeouts,
    originalStops:()=>originalStops,captureCalls:()=>captureCalls,mode:value=>{mode=value;},
    captureError:value=>{forcedCaptureError=value;},resumeGate:value=>{resumeGate=value;},suspendGate:value=>{suspendGate=value;},
    elementSourceCreates:()=>elementSourceCreates,
    enable:value=>window.amatvSetSpectrumActive(value),
    sample:()=>{const frame=[...intervals.values()].find(x=>x.ms===50);assert.ok(frame,'20Hz analysis timer missing');frame.callback();}};
}
(async()=>{
  const f=fixture();f.events.musickitloaded();await flush();
  assert.equal(f.captureCalls(),0,'Disabled spectrum must not create a capture route');
  f.enable(true);await flush();
  let current=f.spectra.at(-1);
  assert.equal(current.status,'capturing');assert.equal(current.captured,true);assert.equal(current.available,true);
  assert.equal(current.rate,48000);assert.ok(current.bass>current.mid&&current.mid>current.treble);
  assert.equal(f.captureCalls(),1);assert.equal(f.originalStops(),0);
  const context=f.contexts[0];assert.equal(context.analyser.fftSize,1024);assert.equal(context.source.connected,context.analyser);
  f.mode('silence');f.sample();current=f.spectra.at(-1);
  assert.equal(current.captured,true);assert.equal(current.available,false,'Actual silence must never become fake energy');
  context.stream.track.muted=true;f.sample();current=f.spectra.at(-1);
  assert.equal(current.captured,false);assert.equal(current.available,false);assert.equal(current.status,'unavailable');
  f.enable(false);await flush();
  assert.equal(context.stream.track.stops,1);assert.equal(context.closed,1);assert.equal(context.source.connected,null);
  assert.equal(f.originalStops(),0);assert.equal([...f.intervals.values()].filter(x=>x.ms===50).length,0);
  assert.equal(f.spectra.at(-1).status,'disabled');
  f.element.mediaKeys={};f.enable(true);await flush();
  assert.equal(f.captureCalls(),1,'Protected media must not be captured');assert.equal(f.spectra.at(-1).status,'unavailable');
  f.enable(false);f.element.mediaKeys=null;f.captureError(true);f.enable(true);await flush();
  assert.equal(f.spectra.at(-1).status,'unavailable');const failedCalls=f.captureCalls();
  for(let i=0;i<3;i++)f.events.playbackStateDidChange();
  assert.equal(f.captureCalls(),failedCalls,'Blocked route must not spin on state polling');assert.equal(f.originalStops(),0);
  const late=fixture();late.events.musickitloaded();await flush();
  let resume;late.resumeGate(new Promise(resolve=>resume=resolve));late.enable(true);late.enable(false);resume();await flush();
  assert.equal(late.contexts[0].closed,1);assert.equal(late.contexts[0].stream.track.stops,1);
  assert.equal([...late.intervals.values()].filter(x=>x.ms===50).length,0,'Late resume must not restart a closed capture');
  assert.equal(late.originalStops(),0);
  const paused=fixture();paused.events.musickitloaded();await flush();paused.enable(true);await flush();
  paused.music.playbackState=3;paused.events.playbackStateDidChange();await flush();
  assert.equal(paused.contexts[0].closed,1);assert.equal(paused.spectra.at(-1).captured,false);assert.equal(paused.originalStops(),0);
  const debug=fixture(true);debug.events.musickitloaded();await flush();debug.enable(true);await flush();
  assert.equal(debug.spectra.at(-1).reason,'signal');assert.equal(debug.spectra.at(-1).captureApi,true);
  assert.equal(debug.spectra.at(-1).contextApi,true);assert.equal(debug.spectra.at(-1).mediaKeys,false);
  debug.mode('silence');debug.sample();assert.equal(debug.spectra.at(-1).reason,'zero');debug.enable(false);
  for(const [reason,setup] of [
    ['media_keys',x=>{x.element.mediaKeys={};}],
    ['missing_capture',x=>{x.element.captureStream=undefined;}],
    ['missing_context',x=>{x.window.AudioContext=undefined;}],
    ['capture_throw',x=>{x.captureError(true);}],
  ]){
    const d=fixture(true);d.events.musickitloaded();await flush();setup(d);d.enable(true);await flush();
    const last=d.spectra.at(-1);assert.equal(last.reason,reason);assert.equal(last.status,'unavailable');
    if(reason==='media_keys')assert.equal(last.mediaKeys,true);
    if(reason==='missing_capture')assert.equal(last.captureApi,false);
    if(reason==='missing_context')assert.equal(last.contextApi,false);
    if(reason==='capture_throw')assert.equal(last.errorName,'SecurityError');
    assert.equal(d.originalStops(),0);
  }
  const probe=fixture(true,true);probe.events.musickitloaded();await flush();
  const mediaKeys={attached:true};probe.element.mediaKeys=mediaKeys;
  const originalSrc=probe.element.currentSrc;probe.enable(true);await flush();
  assert.equal(probe.captureCalls(),0,'Public element source must not call forbidden captureStream');
  const probeContext=probe.contexts[0];assert.equal(probeContext.element,probe.element);
  assert.equal(probeContext.source.connected,probeContext.analyser);assert.equal(probeContext.analyser.connected,probeContext.destination);
  assert.equal(probe.spectra.at(-1).graphMode,'media_element');assert.equal(probe.spectra.at(-1).sameOriginBlob,true);
  assert.equal(probe.spectra.at(-1).available,true);assert.equal(probe.element.mediaKeys,mediaKeys);
  assert.equal(probe.element.currentSrc,originalSrc);assert.equal(probe.elementSourceCreates(),1);
  assert.ok(![...probe.timeouts.values()].some(x=>x.ms===2000),'Production analysis must not expire after two seconds');
  probe.mode('silence');probe.sample();assert.equal(probe.spectra.at(-1).reason,'zero');assert.equal(probe.spectra.at(-1).available,false);
  probe.enable(false);await flush();assert.equal(probeContext.closed,0);assert.equal(probeContext.state,'running');
  assert.equal(probeContext.source.connected,probeContext.analyser);assert.equal(probeContext.analyser.connected,probeContext.destination);
  assert.equal([...probe.intervals.values()].filter(x=>x.ms===50).length,0,'FFT off keeps audio but stops sampling');
  probe.element.paused=true;probe.music.playbackState=3;probe.elementEvents.pause();await flush();
  assert.equal(probeContext.state,'suspended');assert.equal(probeContext.closed,0);
  probe.element.paused=false;probe.music.playbackState=2;probe.elementEvents.playing();await flush();
  assert.equal(probeContext.state,'running','Original audio must resume even when FFT is disabled');
  assert.equal([...probe.intervals.values()].filter(x=>x.ms===50).length,0);
  probe.mode('signal');probe.enable(true);await flush();assert.equal(probe.spectra.at(-1).available,true);
  assert.equal(probe.contexts.length,1);assert.equal(probe.elementSourceCreates(),1,'Same element graph must be reused after FFT toggle/pause');
  probe.element.paused=true;probe.elementEvents.pause();await flush();
  const secondEvents={};
  const second={...probe.element,paused:false,currentSrc:'blob:https://amatv.invalid/second',
    addEventListener:(name,callback)=>{const previous=secondEvents[name];secondEvents[name]=previous ? ()=>{previous();callback();} : callback;}};
  probe.events.mediaElementCreated(second);await flush();
  const secondContext=probe.contexts[1];assert.equal(probe.contexts.length,2);
  assert.equal(probeContext.state,'suspended','Replaced paused element must not retain a running context');
  assert.equal(secondContext.state,'running');assert.equal(probe.elementSourceCreates(),2);
  second.paused=true;secondEvents.pause();await flush();probe.element.paused=false;
  probe.events.mediaElementCreated(probe.element);await flush();
  assert.equal(probeContext.state,'running');assert.equal(secondContext.state,'suspended');
  assert.equal(probe.elementSourceCreates(),2,'Returning to an earlier element must reuse its WeakMap graph');
  probe.enable(false);assert.equal(probeContext.closed,0);assert.equal(probe.originalStops(),0);
  probe.events['window:pagehide']();await flush();assert.equal(probeContext.closed,1);assert.equal(secondContext.closed,1);
  assert.equal(probeContext.source.connected,null);assert.equal(probe.originalStops(),0);
  const graphLate=fixture(true,true);graphLate.events.musickitloaded();await flush();graphLate.element.mediaKeys={};
  let finishResume;graphLate.resumeGate(new Promise(resolve=>finishResume=resolve));graphLate.enable(true);
  graphLate.element.paused=true;graphLate.elementEvents.pause();finishResume();await flush();
  assert.equal(graphLate.contexts[0].state,'suspended','Late resume after real element pause must re-suspend');
  assert.equal([...graphLate.intervals.values()].filter(x=>x.ms===50).length,0);
  graphLate.events['window:pagehide']();await flush();
  const suspendRace=fixture(true,true);suspendRace.events.musickitloaded();await flush();suspendRace.element.mediaKeys={};
  suspendRace.enable(true);await flush();let finishSuspend;
  suspendRace.suspendGate(new Promise(resolve=>finishSuspend=resolve));suspendRace.element.paused=true;suspendRace.elementEvents.pause();
  suspendRace.element.paused=false;suspendRace.elementEvents.playing();finishSuspend();await flush();
  assert.equal(suspendRace.contexts[0].state,'running','Late suspend cannot strand a playing element in silence');
  assert.equal(suspendRace.elementSourceCreates(),1);suspendRace.events['window:pagehide']();await flush();
  const unsafe=fixture(true,true);unsafe.events.musickitloaded();await flush();unsafe.element.mediaKeys={};
  unsafe.element.currentSrc='blob:https://foreign.invalid/fixture';unsafe.enable(true);await flush();
  assert.equal(unsafe.contexts.length,0);assert.equal(unsafe.spectra.at(-1).reason,'media_keys');
  assert.equal(unsafe.spectra.at(-1).sameOriginBlob,false);
  for(const elementProbe of [false,true]){
    const failed=fixture(true,elementProbe);failed.events.musickitloaded();await flush();
    if(elementProbe)failed.element.mediaKeys={};
    failed.mode('sample_error');failed.enable(true);await flush();
    assert.equal(failed.spectra.at(-1).status,'unavailable');
    assert.equal(failed.spectra.at(-1).reason,'sample_throw');
    assert.equal([...failed.intervals.values()].filter(timer=>timer.ms===50).length,0,
      'First-sample failure must clear the already-owned analysis timer');
    failed.events.playbackStateDidChange();await flush();
    assert.equal([...failed.intervals.values()].filter(timer=>timer.ms===50).length,0,
      'State reporting must not recreate a polling timer for the failed route');
    if(elementProbe){
      assert.equal(failed.contexts[0].closed,0);
      assert.equal(failed.contexts[0].analyser.connected,failed.contexts[0].destination,
        'A failed analyser must retain media-element audible passthrough');
    }else assert.equal(failed.contexts[0].closed,1);
    assert.equal(failed.originalStops(),0);
    failed.events['window:pagehide']();await flush();
  }
  console.log('PASS station spectrum: real bands/silence, guarded capture/source passthrough, WeakMap reuse, FFT-off audio, replacement cleanup, pause/resume races');
})().catch(error=>{console.error(error);process.exitCode=1;});
