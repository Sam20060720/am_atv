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
  MusicKit:{PlaybackStates:{playing:2,stopped:4,loading:1,waiting:6,stalled:7,seeking:8,completed:9},
    configure:configuration=>{assert.equal(configuration.storefrontId,'tw');return Promise.resolve(music);},
    formatArtworkURL:a=>a.url.replace('{w}','600').replace('{h}','600')},
  fetch:(url, options)=>new Promise(resolve=>requests.push({url,options,resolve}))};
vm.runInNewContext(inline, context);
const flush = async()=>{for(let i=0;i<10;i++)await Promise.resolve();};
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
  console.log('PASS station bootstrap/account-country readiness, fresh reconnect metadata, exact ID/art, cancellation, finite resume, preview rejection and reconnect ownership');
})().catch(error=>{console.error(error);process.exitCode=1;});
