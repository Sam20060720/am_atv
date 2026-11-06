// Runs the production bootstrap with a fake documented MusicKit/event contract.
// No credentials, accounts, actual network requests or audio output.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const storefrontIds=JSON.parse(fs.readFileSync('app/src/main/assets/apple-storefront-ids.json','utf8'));
assert.equal(storefrontIds.tw,'143470');assert.equal(storefrontIds.us,'143441');assert.equal(storefrontIds.jp,'143462');
const source = fs.readFileSync('app/src/main/java/dev/amatv/integration/MusicKitStationPlayer.kt', 'utf8');
const inline = source.split('</head><body><script>')[1].split('</script><script')[0]
  .replace('$configuration', JSON.stringify({epoch:1, station:'ra.anchor', developer:'fixture', user:'fixture', position:0, storefront:'tw', storefrontNumericId:storefrontIds.tw, build:'test'}))
  .replaceAll('$BRIDGE', 'bridge');
new vm.Script(inline);
const handlers = {}, reports = [], requests = [];
const station = {attributes:{name:'Station', artistName:'Curator'}, isLiveRadioStation:true};
const music = {nowPlayingItem:station, playbackState:2, currentPlaybackDuration:12,
  currentPlaybackTime:4, storefrontCountryCode:'us', accountStorefront:'tw', queue:{length:1,nextPlayableItemIndex:-1,previousPlayableItemIndex:-1},
  addEventListener:(name, callback)=>{handlers[name]=callback;}, setQueue:()=>Promise.resolve(),
  me:function(){assert.equal(this.musicUserToken,'fixture');this.storefrontCountryCode=this.accountStorefront;return Promise.resolve();},
  stop:()=>Promise.resolve(), play:()=>Promise.resolve(), pause:()=>Promise.resolve()};
const context = {console, AbortController, document:{addEventListener:(name, callback)=>{handlers[name]=callback;}},
  bridge:{report:raw=>reports.push(JSON.parse(raw))},
  window:{setTimeout:()=>1,clearTimeout:()=>{},setInterval:()=>1},
  MusicKit:{PlaybackStates:{none:0,playing:2,paused:3,stopped:4,ended:5,loading:1,waiting:6,stalled:7,seeking:8,completed:9},
    configure:configuration=>{assert.equal(configuration.storefrontId,'tw');return Promise.resolve(music);},
    formatArtworkURL:a=>a.url.replace('{w}','600').replace('{h}','600')},
  fetch:(url, options)=>new Promise(resolve=>requests.push({url,options,resolve}))};
vm.runInNewContext(inline, context);
const flush = async()=>{for(let i=0;i<10;i++)await Promise.resolve();};
const withDeadline=(promise,description)=>new Promise((resolve,reject)=>{
  const timeout=setTimeout(()=>reject(new Error(description+' was not observed')),1000);
  promise.then(value=>{clearTimeout(timeout);resolve(value);},error=>{clearTimeout(timeout);reject(error);});
});
const latest = ()=>reports.at(-1);
(async()=>{
  // Configuring an API storefront and assigning a token do not make MusicKit's
  // account country ready. Only its public me() request owns that transition.
  for(const initialCountry of ['us','tw'])for(const settlement of ['account_ready','account_rejected','account_mismatch']){
    const events={},observations=[],calls=[];
    let country=initialCountry,resolveAccount,rejectAccount;
    const backing={...music,nowPlayingItem:station,
      addEventListener:(name,callback)=>{events[name]=callback;},
      me:function(){
        assert.equal(this.musicUserToken,'fixture','Token must be injected before resolving the account');
        calls.push('me');
        return new Promise((resolve,reject)=>{resolveAccount=resolve;rejectAccount=reject;});
      },
      setQueue:()=>{
        calls.push('setQueue');
        if(country!=='cn')return Promise.reject({name:'SDKStorefrontGuard',reason:'CONTENT_EQUIVALENT'});
        return Promise.resolve();
      }};
    for(const property of ['storefrontCountryCode','storefrontId'])Object.defineProperty(backing,property,{
      get:()=>country,
      set:()=>{throw new Error('Application must not forge the SDK account storefront');},
    });
    const configured=inline.replace('"storefront":"tw"','"storefront":"cn"')
      .replace('"storefrontNumericId":"'+storefrontIds.tw+'"','"storefrontNumericId":""');
    vm.runInNewContext(configured,{...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:raw=>observations.push(JSON.parse(raw))},
      MusicKit:{...context.MusicKit,configure:configuration=>{
        assert.equal(configuration.storefrontId,'cn');return Promise.resolve(backing);
      }}});
    events.musickitloaded();await flush();
    assert.deepEqual(calls,['me'],'A pending account request must block setQueue');
    assert.equal(country,initialCountry,'configure() must not mask a cold or previous-account country');
    if(settlement==='account_rejected')rejectAccount({status:401});
    else{
      // Simulate the SDK adopting the authenticated response internally, never
      // an application write to storefrontCountryCode/storefrontId.
      if(settlement==='account_ready')country='cn';
      resolveAccount();
    }
    await flush();
    if(settlement==='account_ready'){
      assert.deepEqual(calls,['me','setQueue']);
      assert.ok(observations.some(report=>report.event==='prepared'));
      assert.ok(!observations.some(report=>report.event==='error'));
    }else{
      assert.deepEqual(calls,['me'],'An unresolved or rejected account must never reach setQueue');
      const failure=observations.at(-1);
      assert.equal(failure.event,'error');
      if(settlement==='account_rejected')assert.equal(failure.reason,'authorization');
      else{
        assert.equal(failure.name,'StationStorefrontMismatch');
        assert.equal(failure.sdkReason,'CONTENT_EQUIVALENT');
      }
      assert.ok(!observations.some(report=>report.event==='prepared'));
    }
  }
  handlers.musickitloaded();await flush();
  assert.equal(latest().title,'');assert.equal(latest().live,true); // Native falls back to the source station.
  handlers.timedMetadataDidChange({title:'One',performer:'Artist',album:'Album',storefrontAdamIds:{TW:'12345'}});
  assert.equal(latest().title,'One');assert.equal(latest().artist,'Artist');assert.equal(latest().artworkUrl,'');
  assert.equal(requests.length,1);assert.equal(requests[0].url,'https://api.music.apple.com/v1/catalog/tw/songs/12345');
  handlers.timedMetadataDidChange({title:'One',performer:'Artist',album:'Album',storefrontAdamIds:{TW:'12345'}});
  assert.equal(requests.length,1);assert.equal(requests[0].options.signal.aborted,false,
    'Repeated same-song metadata must preserve the in-flight lookup');
  requests[0].resolve({ok:true,json:async()=>({data:[{id:'12345',type:'songs',attributes:{name:'Catalog One',artwork:{url:'https://is1-ssl.mzstatic.com/image/one/{w}x{h}.jpg'}}}]})});
  await flush();assert.equal(latest().artworkUrl,'https://is1-ssl.mzstatic.com/image/one/600x600.jpg');
  assert.equal(latest().title,'One'); // Actual stream metadata wins over catalogue spelling.
  const beforeRepeat=reports.length;
  handlers.timedMetadataDidChange({title:'One',performer:'Artist',storefrontAdamIds:{TW:'12345'}});await flush();
  assert.equal(requests.length,1);assert.equal(latest().artworkUrl,'https://is1-ssl.mzstatic.com/image/one/600x600.jpg');
  assert.ok(reports.slice(beforeRepeat).every(report=>report.artworkUrl==='https://is1-ssl.mzstatic.com/image/one/600x600.jpg'),
    'A cached same-song update must never emit a temporary station-cover snapshot');
  handlers.timedMetadataDidChange({title:'Two',storefrontAdamIds:{TW:'23456'}});
  assert.equal(requests.length,2);
  handlers.timedMetadataDidChange({title:'Three',storefrontAdamIds:{US:'34567'}});
  assert.equal(requests[1].options.signal.aborted,true);
  assert.equal(requests.length,2); // Never select another storefront's ID.
  requests[1].resolve({ok:true,json:async()=>({data:[{id:'23456',type:'songs',attributes:{name:'Two',artwork:{url:'https://is1-ssl.mzstatic.com/image/two.jpg'}}}]})});
  await flush();assert.equal(latest().title,'Three');assert.equal(latest().artworkUrl,'');
  const beforeCachedReturn=reports.length;
  handlers.timedMetadataDidChange({title:'One returns',storefrontAdamIds:{TW:'12345'}});
  assert.equal(requests.length,2);
  assert.ok(reports.slice(beforeCachedReturn).every(report=>report.artworkUrl==='https://is1-ssl.mzstatic.com/image/one/600x600.jpg'),
    'Returning to a cached real song must publish its cover immediately');
  handlers.timedMetadataDidChange({});assert.equal(latest().title,''); // Native station fallback, not previous-song art.
  music.nowPlayingItem={attributes:{name:'Generated Song',artistName:'Generated Artist',albumName:'Generated Album',
    artwork:{url:'https://is1-ssl.mzstatic.com/image/generated.jpg'}}};
  // Station live latch is intentionally retained for this live session; a fresh
  // session tests generated-station presentation via the static projector tests.
  handlers.nowPlayingItemDidChange();assert.equal(latest().title,'');
  // Actual live streams can publish song MediaItems without emitting ID3.
  // Only the current-item event is fresh; old polling after reconnect is not.
  const freshSong={id:'67890',type:'song',attributes:{name:'Fresh SDK Song',artistName:'Fresh Artist',
    artwork:{url:'https://is1-ssl.mzstatic.com/image/fresh/{w}x{h}.jpg'}}};
  music.nowPlayingItem=freshSong;
  handlers.nowPlayingItemDidChange({item:freshSong});
  assert.equal(latest().title,'Fresh SDK Song');
  assert.equal(latest().artworkUrl,'https://is1-ssl.mzstatic.com/image/fresh/600x600.jpg');
  const requestCount=requests.length;
  handlers.timedMetadataDidChange({title:'Fresh SDK Song',performer:'Fresh Artist',storefrontAdamIds:{TW:'67890'}});
  assert.equal(latest().artworkUrl,'https://is1-ssl.mzstatic.com/image/fresh/600x600.jpg',
    'Matching ID3 must keep the already received SDK song cover');
  handlers.nowPlayingItemDidChange({item:freshSong});
  assert.equal(requests.at(-1).options.signal.aborted,false,
    'Same-song current-item notification must not abort its matching ID3 lookup');
  const nextSong={id:'78901',type:'song',attributes:{name:'Next SDK Song',artistName:'Next Artist',
    artwork:{url:'https://is1-ssl.mzstatic.com/image/next.jpg'}}};
  music.nowPlayingItem=nextSong;
  handlers.nowPlayingItemDidChange({item:nextSong});
  assert.equal(latest().title,'Next SDK Song');
  assert.equal(latest().artworkUrl,'https://is1-ssl.mzstatic.com/image/next.jpg');
  handlers.nowPlayingItemDidChange({item:freshSong});
  assert.equal(latest().title,'Next SDK Song','A late old-item notification must not restore an old cover');
  assert.equal(requests[requestCount].options.signal.aborted,true,
    'A genuinely new SDK song must cancel the previous song lookup');
  const originalStop=music.stop,originalPlay=music.play;
  music.stop=function(){this.playbackState=4;return Promise.resolve();};
  music.play=function(){this.playbackState=2;return Promise.resolve();};
  context.window.amatvStationCommand('pause');await flush();
  context.window.amatvStationCommand('play');await flush();
  const reconnectReport=reports.slice().reverse().find(report=>typeof report.title==='string');
  assert.equal(reconnectReport.title,'');assert.equal(reconnectReport.artworkUrl,'',
    'Reconnect polling must not reuse the SDK item retained from the previous connection');
  handlers.nowPlayingItemDidChange({item:nextSong});
  assert.equal(latest().artworkUrl,'https://is1-ssl.mzstatic.com/image/next.jpg');
  music.nowPlayingItem=station;
  handlers.nowPlayingItemDidChange({item:station});
  assert.equal(latest().title,'');assert.equal(latest().artworkUrl,'');
  music.stop=originalStop;music.play=originalPlay;
  for (const [error, expected] of [
    [{status:'401'},'authorization'], [{statusCode:403},'authorization'],
    [{status:404},'unavailable'], [{status:503},'network'],
    [{name:'NetworkError'},'network'], [{name:'MediaError',code:2},'network'],
    [{code:123},'playback'],
  ]) {
    const localHandlers = {}, localReports = [];
    const localMusic = {...music, addEventListener:(name, callback)=>{localHandlers[name]=callback;}};
    const local = {...context,
      document:{addEventListener:(name, callback)=>{localHandlers[name]=callback;}},
      bridge:{report:raw=>localReports.push(JSON.parse(raw))},
      MusicKit:{...context.MusicKit, configure:()=>Promise.resolve(localMusic)},
    };
    vm.runInNewContext(inline, local);
    localHandlers.musickitloaded(); await flush();
    localHandlers.mediaPlaybackError({error}); await flush();
    assert.equal(localReports.at(-1).reason, expected, JSON.stringify(error));
  }
  // None/Stopped after actual playback remain eligible for the existing
  // native progress deadline. A normal pause or queue completion does not;
  // reporting this health signal must not invent Playing/Buffering UI state.
  {
    const events={},observations=[];
    const states=context.MusicKit.PlaybackStates;
    const backing={...music,playbackState:states.playing,
      addEventListener:(name,callback)=>{events[name]=callback;}};
    const environment={...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:raw=>observations.push(JSON.parse(raw))},
      MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(backing)}};
    vm.runInNewContext(inline,environment);events.musickitloaded();await flush();
    environment.window.amatvStationCommand('play',0);await flush();
    assert.equal(observations.at(-1).playing,true);
    for(const state of ['none','stopped','paused','ended','completed']){
      backing.playbackState=states[state];events.playbackStateDidChange();
      const report=observations.at(-1);
      assert.equal(report.idle,state==='none' || state==='stopped',state);
      assert.equal(report.playing,false,state);assert.equal(report.buffering,false,state);
    }
    environment.window.amatvStationCommand('pause',0);await flush();
    backing.playbackState=states.stopped;events.playbackStateDidChange();
    assert.equal(observations.at(-1).idle,false,'User Pause must disable the idle deadline');
  }
  // A failed stream may also make the SDK's Stop throw synchronously. Cleanup
  // must still report the original network error: terminal suppresses future
  // state reports, and the native startup deadline ended on the first Playing.
  {
    const events={},observations=[];
    let stops=0;
    const backing={...music,playbackState:2,
      addEventListener:(name,callback)=>{events[name]=callback;},
      stop:()=>{stops++;throw Object.assign(new Error('Stop failed'),{name:'InvalidStateError'});}};
    const environment={...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:raw=>observations.push(JSON.parse(raw))},
      MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(backing)}};
    vm.runInNewContext(inline,environment);events.musickitloaded();await flush();
    environment.window.amatvStationCommand('play',0);await flush();
    assert.equal(observations.at(-1).playing,true);
    backing.playbackState=6;events.playbackStateDidChange();
    assert.equal(observations.at(-1).buffering,true);
    assert.doesNotThrow(()=>events.mediaPlaybackError({error:{name:'NetworkError'}}));
    await flush();
    const failures=observations.filter(report=>report.event==='error');
    assert.equal(failures.length,1);assert.equal(failures[0].reason,'network');
    assert.equal(observations.at(-1).event,'error');assert.equal(stops,1);
    events.mediaPlaybackError({error:{name:'NetworkError'}});events.playbackStateDidChange();
    assert.equal(observations.filter(report=>report.event==='error').length,1);
    assert.equal(observations.at(-1).event,'error','A dead stream must not overwrite its terminal failure');
  }
  assert.ok(!reports.some(report=>'metadataFields' in report), 'Standalone diagnostics must remain disabled');
  const diagnostics=[], diagnosticHandlers={}, diagnosticRequests=[];
  const diagnosticMusic={...music,nowPlayingItem:station,
    addEventListener:(name,callback)=>{diagnosticHandlers[name]=callback;}};
  const diagnosticContext={...context,
    document:{addEventListener:(name,callback)=>{diagnosticHandlers[name]=callback;}},
    bridge:{report:raw=>diagnostics.push(JSON.parse(raw))},
    MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(diagnosticMusic)},
    fetch:(url,options)=>new Promise(resolve=>diagnosticRequests.push({url,options,resolve}))};
  const diagnosticInline=inline.replace('"build":"test"','"build":"test","metadataDiagnostics":true');
  vm.runInNewContext(diagnosticInline,diagnosticContext);
  diagnosticHandlers.musickitloaded();await flush();
  diagnosticHandlers.timedMetadataDidChange({title:'Private Title',performer:'Private Artist',storefrontAdamIds:{TW:'87654',US:'98765'}});
  diagnosticRequests[0].resolve({ok:false,status:503});await flush();
  const metadataDiagnostics=diagnostics.filter(report=>'metadataFields' in report);
  assert.ok(metadataDiagnostics.some(report=>report.stage==='metadata_exact_id' && report.idCountries==='TW,US' && report.country==='tw'));
  assert.ok(metadataDiagnostics.some(report=>report.stage==='metadata_lookup_http' && report.lookupStatus===503));
  assert.ok(metadataDiagnostics.some(report=>report.stage==='metadata_lookup_failed'));
  assert.ok(metadataDiagnostics.every(report=>!JSON.stringify(report).includes('Private Title') && !JSON.stringify(report).includes('87654') && !JSON.stringify(report).includes('fixture')),
    'Diagnostic records contain names/values/credentials');
  diagnosticHandlers.timedMetadataDidChange({title:'Numeric keys',performer:'Artist',storefrontAdamIds:{'143462':'87654'}});
  const numericDiagnostic=diagnostics.filter(report=>'metadataFields' in report).at(-1);
  assert.equal(numericDiagnostic.stage,'metadata_missing_id');assert.equal(numericDiagnostic.idCountries,'143462');
  assert.equal(diagnosticRequests.length,1,'Do not guess a numeric storefront mapping');
  for (const fixture of [
    {country:'tw',ids:{'143441':'99999','143470':'12345'},expected:'https://api.music.apple.com/v1/catalog/tw/songs/12345'},
    {country:'tw',ids:{tw:'12345'},expected:'https://api.music.apple.com/v1/catalog/tw/songs/12345'},
    {country:'tw',ids:{TW:'12345'},expected:'https://api.music.apple.com/v1/catalog/tw/songs/12345'},
    {country:'tw',ids:{'143441':'99999','143462':'88888'},expected:null},
    {country:'us',ids:{'143441':'99999','143470':'12345'},expected:'https://api.music.apple.com/v1/catalog/us/songs/99999'},
    {country:'jp',ids:{'143441':'99999','143462':'88888'},expected:'https://api.music.apple.com/v1/catalog/jp/songs/88888'},
  ]) {
    const events={},calls=[];
    const wrongDefaultMusic={...music,storefrontCountryCode:'us',storefrontId:'us',accountStorefront:fixture.country,nowPlayingItem:station,
      addEventListener:(name,callback)=>{events[name]=callback;}};
    const environment={...context,document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:()=>{}},MusicKit:{...context.MusicKit,configure:configuration=>{
        assert.equal(configuration.storefrontId,fixture.country);return Promise.resolve(wrongDefaultMusic);
      }},
      fetch:(url,options)=>{calls.push(url);return new Promise(()=>{});}};
    const configured=inline.replace('"storefront":"tw"','"storefront":"'+fixture.country+'"')
      .replace('"storefrontNumericId":"'+storefrontIds.tw+'"','"storefrontNumericId":"'+storefrontIds[fixture.country]+'"');
    vm.runInNewContext(configured,environment);events.musickitloaded();await flush();
    // Metadata lookup must still use the configured account region even if a
    // later SDK metadata wrapper exposes a stale default country.
    wrongDefaultMusic.storefrontCountryCode='us';
    events.timedMetadataDidChange({title:'Stream title',performer:'Stream artist',storefrontAdamIds:fixture.ids});
    assert.deepEqual(calls,fixture.expected ? [fixture.expected] : [], JSON.stringify(fixture));
  }
  // A new live connection must start from the source station, even when the
  // SDK retains the previous programme item. Only new stream metadata may
  // replace it; repeated ID3 within one connection still keeps the cover cache.
  const restartEvents={},restartReports=[],restartRequests=[],restartQueues=[];
  let firstQueue=true,finishStop;
  const staleItem={isLiveRadioStation:true,attributes:{name:'Previous programme',artistName:'Previous artist',
    artwork:{url:'https://is1-ssl.mzstatic.com/image/previous.jpg'}}};
  const restartMusic={...music,nowPlayingItem:staleItem,playbackState:2,
    addEventListener:(name,callback)=>{restartEvents[name]=callback;},
    setQueue:()=>{
      if(firstQueue){firstQueue=false;return Promise.resolve();}
      return new Promise(resolve=>restartQueues.push(resolve));
    },
    stop:()=>new Promise(resolve=>{finishStop=()=>{restartMusic.playbackState=4;resolve();};}),
    play:()=>{restartMusic.playbackState=2;restartEvents.playbackStateDidChange();return Promise.resolve();}};
  const restartContext={...context,window:{...context.window},
    document:{addEventListener:(name,callback)=>{restartEvents[name]=callback;}},
    bridge:{report:raw=>restartReports.push(JSON.parse(raw))},
    MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(restartMusic)},
    fetch:(url,options)=>new Promise(resolve=>restartRequests.push({url,options,resolve}))};
  const restartLatest=()=>restartReports.filter(report=>'title' in report).at(-1);
  const songMetadata=title=>({title,performer:'Current artist',storefrontAdamIds:{TW:'12345'}});
  const songResponse=art=>({ok:true,json:async()=>({data:[{id:'12345',type:'songs',attributes:{
    name:'Catalog song',artwork:{url:'https://is1-ssl.mzstatic.com/image/'+art+'.jpg'}}}]})});
  vm.runInNewContext(inline,restartContext);restartEvents.musickitloaded();await flush();
  assert.equal(restartLatest().title,'');assert.equal(restartLatest().artworkUrl,'');
  restartEvents.timedMetadataDidChange(songMetadata('Old stream song'));
  restartRequests[0].resolve(songResponse('old-stream'));await flush();
  assert.equal(restartLatest().artworkUrl,'https://is1-ssl.mzstatic.com/image/old-stream.jpg');
  restartEvents.timedMetadataDidChange({title:'Pending old stream',storefrontAdamIds:{TW:'23456'}});
  assert.equal(restartRequests.length,2);
  restartContext.window.amatvStationCommand('pause',0);await flush();
  assert.equal(restartRequests[1].options.signal.aborted,true);
  assert.equal(restartLatest().title,'');assert.equal(restartLatest().artworkUrl,'');
  restartEvents.timedMetadataDidChange(songMetadata('Late stopped stream'));
  restartContext.window.amatvStationCommand('play',0);await flush();
  restartEvents.timedMetadataDidChange(songMetadata('Late stopping stream'));
  assert.equal(restartRequests.length,2,'Stopped stream events must not fetch or restore old metadata');
  assert.equal(restartQueues.length,0,'Reconnect waits for the actual Stop');
  finishStop();await flush();
  assert.equal(restartQueues.length,1);
  assert.equal(restartLatest().title,'');assert.equal(restartLatest().artworkUrl,'');
  // The first new ID3 can precede Playing and setQueue settlement. It must be
  // accepted and fetched again, even when its song ID was cached last time.
  restartEvents.timedMetadataDidChange(songMetadata('Fresh stream song'));
  assert.equal(restartRequests.length,3);
  assert.equal(restartRequests[2].options.cache,'no-store');
  assert.equal(restartLatest().title,'Fresh stream song');assert.equal(restartLatest().artworkUrl,'');
  restartRequests[2].resolve(songResponse('fresh-stream'));await flush();
  restartRequests[1].resolve({ok:true,json:async()=>({data:[{id:'23456',type:'songs',attributes:{
    name:'Stale response',artwork:{url:'https://is1-ssl.mzstatic.com/image/stale-response.jpg'}}}]})});
  await flush();
  assert.equal(restartLatest().title,'Fresh stream song');
  assert.equal(restartLatest().artworkUrl,'https://is1-ssl.mzstatic.com/image/fresh-stream.jpg');
  restartQueues[0]();await flush();
  assert.equal(restartLatest().title,'Fresh stream song');
  assert.equal(restartLatest().artworkUrl,'https://is1-ssl.mzstatic.com/image/fresh-stream.jpg');
  // A finite programme shares the station engine, but resumes its cursor and
  // uses Pause/Play rather than the live stream's Stop/reconnect path.
  const episodeEvents={},episodeReports=[],episodeCalls=[];
  const episodeMusic={...music,
    nowPlayingItem:{isLiveRadioStation:false,attributes:{name:'Episode',artistName:'Host'}},
    playbackState:3,currentPlaybackTime:42,currentPlaybackDuration:120,
    addEventListener:(name,callback)=>{episodeEvents[name]=callback;},
    seekToTime:position=>{episodeCalls.push(['seek',position]);return Promise.resolve();},
    play:()=>{episodeCalls.push(['play']);return Promise.resolve();},
    pause:()=>{episodeCalls.push(['pause']);return Promise.resolve();},
    stop:()=>{episodeCalls.push(['stop']);return Promise.resolve();}};
  const episodeContext={...context,
    document:{addEventListener:(name,callback)=>{episodeEvents[name]=callback;}},
    window:{...context.window},bridge:{report:raw=>episodeReports.push(JSON.parse(raw))},
    MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(episodeMusic)}};
  vm.runInNewContext(inline.replace('"position":0','"position":42'),episodeContext);
  episodeEvents.musickitloaded();await flush();
  assert.deepEqual(episodeCalls,[['seek',42]]);
  assert.equal(episodeReports.at(-1).live,false);assert.equal(episodeReports.at(-1).seek,true);
  assert.equal(episodeReports.at(-1).durationMs,120_000);
  episodeContext.window.amatvStationCommand('play',0);await flush();
  episodeContext.window.amatvStationCommand('pause',0);await flush();
  assert.ok(episodeCalls.some(call=>call[0]==='play'));
  assert.ok(episodeCalls.some(call=>call[0]==='pause'));
  assert.ok(!episodeCalls.some(call=>call[0]==='stop'),'Episode Pause must retain the finite item');
  episodeMusic.playbackState=9;episodeEvents.playbackStateDidChange();
  assert.equal(episodeReports.at(-1).ended,true);
  // A finite episode's first Play can finish after a completed Pause. Its
  // success must reapply the current Pause; only that explicit intent owns
  // the correction, including a rejection that arrives after a newer Play.
  for(const scenario of ['late_play_success','superseded_pause_error','current_pause_throw','terminal_stop_throw']){
    const events={},observations=[],pendingPlays=[];
    const states=context.MusicKit.PlaybackStates;
    let pauseCalls=0,stopCalls=0,rejectCorrection,reportTerminal,markLateStop;
    const terminalReported=new Promise(resolve=>{reportTerminal=resolve;});
    const lateStopCalled=new Promise(resolve=>{markLateStop=resolve;});
    const backing={...music,
      nowPlayingItem:{isLiveRadioStation:false,attributes:{name:'Episode',artistName:'Host'}},
      playbackState:states.paused,
      addEventListener:(name,callback)=>{events[name]=callback;},
      play:()=>new Promise(resolve=>pendingPlays.push(()=>{
        backing.playbackState=states.playing;events.playbackStateDidChange();resolve();
      })),
      pause:()=>{
        pauseCalls++;
        if(scenario==='current_pause_throw' && pauseCalls===2)
          throw Object.assign(new Error('Pause cleanup failed'),{name:'NetworkError'});
        backing.playbackState=states.paused;events.playbackStateDidChange();
        if(scenario==='superseded_pause_error' && pauseCalls===3)
          return new Promise((resolve,reject)=>{rejectCorrection=reject;});
        return Promise.resolve();
      },
      stop:()=>{
        stopCalls++;
        if(scenario==='terminal_stop_throw' && stopCalls===2){
          markLateStop();
          throw Object.assign(new Error('Stop cleanup failed'),{name:'InvalidStateError'});
        }
        backing.playbackState=states.stopped;return Promise.resolve();
      }};
    const environment={...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:raw=>{
        const report=JSON.parse(raw);observations.push(report);
        if(report.event==='error')reportTerminal(report);
      }},
      MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(backing)}};
    vm.runInNewContext(inline,environment);events.musickitloaded();await flush();
    const command=action=>environment.window.amatvStationCommand(action,0);
    command('play');await flush();assert.equal(pendingPlays.length,1);
    if(scenario==='terminal_stop_throw'){
      events.mediaPlaybackError({error:{name:'NetworkError'}});
      const failure=await withDeadline(terminalReported,'Terminal network error');
      assert.equal(failure.reason,'network');
      pendingPlays[0]();await withDeadline(lateStopCalled,'Stop after late Play');
      assert.equal(stopCalls,2,'Late successful work must stop its terminal stream');
      assert.equal(observations.filter(report=>report.event==='error').length,1);
      assert.equal(observations.filter(report=>report.event!=='diagnostic').at(-1).event,'error',
        'Late diagnostic messages must not be mistaken for playback state');
      continue;
    }
    command('pause');
    if(scenario==='current_pause_throw'){
      const failure=await withDeadline(terminalReported,'Pause cleanup terminal error');
      assert.equal(failure.event,'error');assert.equal(failure.reason,'network');
      continue;
    }
    await flush();
    assert.equal(backing.playbackState,states.paused);
    pendingPlays[0]();await flush();
    assert.equal(backing.playbackState,states.paused,'A late first Play must preserve the latest Pause');
    if(scenario==='superseded_pause_error'){
      assert.equal(typeof rejectCorrection,'function');
      command('play');await flush();assert.equal(pendingPlays.length,2);
      pendingPlays[1]();await flush();
      rejectCorrection(Object.assign(new Error('Old correction failed'),{name:'NetworkError'}));await flush();
      assert.equal(backing.playbackState,states.playing);
      assert.equal(observations.at(-1).playing,true);
    }else assert.equal(observations.at(-1).playing,false);
    assert.ok(!observations.some(report=>report.event==='error'),scenario);
  }
  for(const scenario of ['deferred_episode','deferred_live','explicit_seek','skip']){
    const events={},calls=[];
    const backing={...episodeMusic,nowPlayingItem:undefined,currentPlaybackDuration:0,
      addEventListener:(name,callback)=>{events[name]=callback;},
      seekToTime:position=>{calls.push(['seek',position]);return Promise.resolve();},
      skipToNextItem:()=>{calls.push(['next']);return Promise.resolve();}};
    const environment={...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},bridge:{report:()=>{}},
      MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(backing)}};
    vm.runInNewContext(inline.replace('"position":0','"position":42'),environment);
    events.musickitloaded();await flush();
    assert.deepEqual(calls,[],'setQueue may resolve with no item or finite duration');
    if(scenario==='explicit_seek')environment.window.amatvStationCommand('seek',18);
    if(scenario==='skip')environment.window.amatvStationCommand('next',0);
    await flush();
    backing.nowPlayingItem={isLiveRadioStation:scenario==='deferred_live',attributes:{name:'Actual item'}};
    events.nowPlayingItemDidChange();await flush();
    assert.ok(!calls.some(call=>call[0]==='seek' && call[1]===42),'Missing duration must not consume the saved cursor');
    backing.currentPlaybackDuration=120;events.playbackDurationDidChange();await flush();
    events.playbackDurationDidChange();await flush();
    assert.deepEqual(calls,scenario==='deferred_episode' ? [['seek',42]] : scenario==='explicit_seek' ? [['seek',18]] : scenario==='skip' ? [['next']] : [],scenario);
  }
  // A station preview is an error, never a successful full episode result.
  const previewEvents={},previewReports=[];
  let previewStops=0;
  const previewMusic={...episodeMusic,nowPlayingItem:{playbackType:1,attributes:{name:'Preview'}},
    addEventListener:(name,callback)=>{previewEvents[name]=callback;},
    stop:()=>{previewStops++;return Promise.resolve();}};
  vm.runInNewContext(inline,{...context,window:{...context.window},
    document:{addEventListener:(name,callback)=>{previewEvents[name]=callback;}},
    bridge:{report:raw=>previewReports.push(JSON.parse(raw))},
    MusicKit:{...context.MusicKit,PlaybackType:{preview:1},configure:()=>Promise.resolve(previewMusic)}});
  previewEvents.musickitloaded();await flush();
  assert.equal(previewReports.at(-1).event,'error');assert.equal(previewReports.at(-1).reason,'authorization');
  assert.equal(previewStops,1);
  for(const settlement of ['resolve','reject']){
    const events={},observations=[],reconnects=[];
    let initialQueue=true,stops=0,plays=0;
    const backing={...music,nowPlayingItem:station,playbackState:2,
      addEventListener:(name,callback)=>{events[name]=callback;},
      setQueue:()=>{
        if(initialQueue){initialQueue=false;return Promise.resolve();}
        return new Promise((resolve,reject)=>reconnects.push({resolve,reject}));
      },
      stop:()=>{stops++;backing.playbackState=4;backing.nowPlayingItem=undefined;return Promise.resolve();},
      play:()=>{plays++;backing.playbackState=2;backing.nowPlayingItem=station;events.playbackStateDidChange();return Promise.resolve();}};
    const environment={...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:raw=>observations.push(JSON.parse(raw))},
      MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(backing)}};
    vm.runInNewContext(inline,environment);events.musickitloaded();await flush();
    const command=action=>environment.window.amatvStationCommand(action,0);
    command('pause');await flush();assert.equal(stops,1);
    command('play');await flush();assert.equal(reconnects.length,1);
    command('pause');await flush();assert.equal(stops,2);
    command('play');await flush();assert.equal(reconnects.length,2);
    // Let the newest reconnect play before the superseded request settles.
    reconnects[1].resolve();await flush();assert.equal(plays,1);assert.equal(backing.playbackState,2);
    if(settlement==='resolve')reconnects[0].resolve();
    else reconnects[0].reject(Object.assign(new Error('Stale reconnect'),{name:'NetworkError'}));
    await flush();
    assert.equal(stops,2,'A stale '+settlement+' must not stop the newer Play');
    assert.equal(plays,1);assert.equal(backing.playbackState,2);
    assert.ok(!observations.some(report=>report.event==='error'),'A stale rejection must not fail the active station');
    // The same error on the current request must still stop/report failure.
    command('pause');await flush();command('play');await flush();
    reconnects[2].reject(Object.assign(new Error('Current reconnect'),{name:'NetworkError'}));await flush();
    assert.equal(observations.at(-1).event,'error');assert.equal(observations.at(-1).reason,'network');
    assert.equal(backing.playbackState,4);
  }
  for(const [error,reason] of [
    [{name:'NETWORK_ERROR'},'network'],
    [{reason:'NETWORK_ERROR'},'network'],
    [{name:'SDKError',data:{status:503}},'network'],
    [{name:'AUTHORIZATION_ERROR'},'playback'],
    [{name:'AUTHORIZATION_ERROR',data:{status:401}},'authorization'],
  ]){
    const events={},observations=[];
    const backing={...music,addEventListener:(name,callback)=>{events[name]=callback;}};
    vm.runInNewContext(inline,{...context,window:{...context.window},
      document:{addEventListener:(name,callback)=>{events[name]=callback;}},
      bridge:{report:raw=>observations.push(JSON.parse(raw))},
      MusicKit:{...context.MusicKit,configure:()=>Promise.resolve(backing)}});
    events.musickitloaded();await flush();
    events.mediaPlaybackError(error);await flush();
    assert.equal(observations.at(-1).reason,reason,JSON.stringify(error));
  }
  console.log('PASS station bootstrap/account-country readiness, fresh reconnect metadata, exact ID/art, cancellation, finite resume, preview rejection and reconnect ownership');
})().catch(error=>{console.error(error);process.exitCode=1;});
