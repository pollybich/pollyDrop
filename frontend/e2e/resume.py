"""Real browser recovery: dropped acknowledgements, page/process restart and Range delivery."""
import os,json,re,time,subprocess
from pathlib import Path
from playwright.sync_api import sync_playwright,expect

BASE=os.environ.get('BASE_URL','http://127.0.0.1:8088')
CHUNK=1024*1024
with sync_playwright() as p:
    engine=os.environ.get('BROWSER','chromium');launch={}
    if os.environ.get('CHROMIUM_PATH'):launch['executable_path']=os.environ['CHROMIUM_PATH']
    if engine=='chromium':launch['args']=['--no-sandbox']
    browser=getattr(p,engine).launch(**launch)
    device=p.devices['iPhone 13'] if engine=='webkit' else {'viewport':{'width':390,'height':844}}
    context=browser.new_context(**device,accept_downloads=True)
    context.route('**/api/locale',lambda route:route.fulfill(json={'country':'US','language':'en'}))
    page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
    payload=bytes(range(256))*(3*CHUNK//256)+b'pollyDrop resume test'
    network={'blocked':True,'lost_chunk_ack':False,'lost_finish_ack':False,'chunk_offsets':[],'created':None}
    def protocol(route):
        req=route.request
        if req.method=='PATCH':
            offset=int(req.headers['upload-offset']);network['chunk_offsets'].append(offset)
            if offset>=CHUNK and network['blocked']:route.abort('failed');return
            if offset==CHUNK and not network['lost_chunk_ack']:
                # Forward the exact binary fixture: intercepted WebKit postData may be UTF-8 serialized.
                observed=req.post_data_buffer or b''
                if observed!=payload[offset:offset+CHUNK]:
                    prefix='::notice title=Binary interception::' if os.environ.get('GITHUB_ACTIONS') else 'INFO: '
                    print(prefix+f'{engine} interception exposed {len(observed)} bytes for a {CHUNK}-byte chunk; forwarding the original fixture bytes.')
                response=route.fetch(post_data=payload[offset:offset+CHUNK]);assert response.ok, f"Forwarded chunk failed: {response.status} {response.text()}"
                network['lost_chunk_ack']=True;route.abort('failed');return
        if req.method=='POST' and req.url.endswith('/complete') and not network['lost_finish_ack']:
            response=route.fetch();assert response.ok, f"Forwarded {req.method} failed: {response.status} {response.text()} (body={len(req.post_data_buffer or b'')})"
            network['created']=response.json();network['lost_finish_ack']=True;route.abort('failed');return
        route.continue_()
    context.route('**/api/uploads/**',protocol)
    page.goto(BASE)
    page.get_by_label('Delete after',exact=True).select_option('600')
    page.get_by_role('switch',name='Protect with a password',exact=True).click()
    page.get_by_label('Transfer password',exact=True).fill('resume-password')
    file={'name':'resume.bin','mimeType':'application/octet-stream','buffer':payload}
    page.get_by_label('Choose a file',exact=True).set_input_files(file)
    page.get_by_role('button',name='Create link',exact=True).click()
    expect(page.get_by_role('button',name='Waiting for connection…',exact=True)).to_be_visible(timeout=20000)
    page.get_by_role('button',name='Pause',exact=True).click()
    expect(page.get_by_role('button',name='Resume upload',exact=True)).to_be_visible()
    saved=page.evaluate("JSON.parse(localStorage.getItem('pollydrop.upload'))")
    assert saved['offset']==CHUNK and saved['passwordProtected']
    assert 'resume-password' not in json.dumps(saved)
    status=context.request.get(BASE+'/api/uploads/'+saved['secret']);assert status.ok and status.json()['offset']==CHUNK
    original_expiry=status.json()['expiresAt']
    # An optional real backend restart verifies durable database/encrypted-part recovery.
    project=os.environ.get('RESUME_RESTART_PROJECT')
    if project:
        args=['docker','compose','-p',project]
        for compose_file in os.environ.get('RESUME_COMPOSE_FILES','compose.yaml').split(':'):args+=['-f',compose_file]
        subprocess.run(args+['restart','backend'],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        for _ in range(90):
            try:
                health=context.request.get(BASE+'/api/health')
                if health.ok:break
            except Exception:pass
            time.sleep(.5)
        else:raise AssertionError('Backend failed to recover')
        status=context.request.get(BASE+'/api/uploads/'+saved['secret']);assert status.ok and status.json()['offset']==CHUNK
    page.reload()
    expect(page.get_by_text('Progress saved',exact=True)).to_be_visible()
    expect(page.get_by_label('Delete after',exact=True)).to_have_value('600')
    expect(page.get_by_label('Delete after',exact=True)).to_be_disabled()
    expect(page.get_by_text('The password from the saved upload is being used.',exact=True)).to_be_visible()
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
    assert not re.search('[А-Яа-яЁё]',page.locator('body').inner_text())
    # Selecting another same-sized file must not overwrite or abandon saved progress.
    altered=bytes([payload[0]^1])+payload[1:]
    page.get_by_label('Choose a file',exact=True).set_input_files({**file,'buffer':altered})
    page.get_by_role('button',name='Resume upload',exact=True).click()
    expect(page.get_by_role('alert')).to_contain_text('Select the original file')
    assert context.request.get(BASE+'/api/uploads/'+saved['secret']).json()['offset']==CHUNK
    network['blocked']=False
    page.get_by_label('Choose a file',exact=True).set_input_files(file)
    page.get_by_role('button',name='Resume upload',exact=True).click()
    expect(page.get_by_role('heading',name='Ready to share.',exact=True)).to_be_visible(timeout=30000)
    assert network['lost_chunk_ack'] and network['lost_finish_ack']
    assert network['chunk_offsets'].count(0)==1,'Already acknowledged data was resent'
    assert network['chunk_offsets'].count(CHUNK)>=2,'Lost chunk acknowledgement was not retried'
    read=page.get_by_label('Recipient link',exact=True).input_value()
    assert read.endswith(network['created']['readToken'])
    assert page.evaluate("localStorage.getItem('pollydrop.upload')") is None
    page.get_by_text('Revoke and manage',exact=True).click();manage=page.get_by_label('Management link',exact=True).input_value()
    status=context.request.get(BASE+'/api/uploads/'+saved['secret']).json()
    assert status['completed'] and status['expiresAt']==original_expiry
    # Ordinary browser download remains an HTTP attachment, byte-for-byte correct.
    recipient=context.new_page();recipient.goto(read)
    recipient.get_by_label('Sender’s password',exact=True).fill('resume-password')
    with recipient.expect_download() as info:recipient.get_by_role('button',name='Download file',exact=True).click()
    download=info.value;assert download.url.startswith(BASE+'/api/downloads/')
    assert Path(download.path()).read_bytes()==payload
    expect(recipient.get_by_role('heading',name='Now it is yours.',exact=True)).to_be_visible(timeout=10000)
    page.goto(manage);expect(page.get_by_text('1 of 1',exact=True)).to_be_visible()
    # A separate single-use transfer verifies partial HTTP delivery does not spend the quota.
    binary=bytes(range(256))*1000
    response=context.request.post(BASE+'/api/transfers',multipart={
        'options':{'name':'options.json','mimeType':'application/json','buffer':json.dumps({'kind':'FILE','ttlSeconds':600,'maxDownloads':1}).encode()},
        'file':{'name':'range.bin','mimeType':'application/octet-stream','buffer':binary}})
    assert response.ok,response.status
    created=response.json();ticket=context.request.post(BASE+'/api/transfers/'+created['readToken']+'/download-ticket',data={}).json()
    first=context.request.get(BASE+ticket['url'],headers={'Range':'bytes=0-65535'})
    assert first.status==206 and first.body()==binary[:65536]
    assert context.request.get(BASE+'/api/manage/'+created['manageToken']).json()['downloads']==0
    last=context.request.get(BASE+ticket['url'],headers={'Range':'bytes=65536-','If-Range':first.headers['etag']})
    assert last.status==206 and last.body()==binary[65536:]
    for _ in range(50):
        if context.request.get(BASE+ticket['statusUrl']).json()['state']=='SENT':break
        time.sleep(.05)
    else:raise AssertionError('Completed range session was not acknowledged')
    assert context.request.get(BASE+'/api/manage/'+created['manageToken']).json()['downloads']==1
    assert context.request.get(BASE+ticket['url']).status==410
    assert context.request.get(BASE+'/api/transfers/'+created['readToken']).json()['state']=='EXHAUSTED'
    # Restored upload controls also translate correctly into Russian.
    page.goto(BASE);page.get_by_role('button',name='RU',exact=True).click()
    expect(page.get_by_role('button',name='Создать ссылку',exact=True)).to_be_visible()
    assert not errors,errors
    context.close();browser.close()
print('PASS ['+engine+']: upload resumes after page/process restart, changed file rejected, lost chunk/final acknowledgements recovered, original expiry/password retained, native download bytes correct, Range continuation spends once.')
