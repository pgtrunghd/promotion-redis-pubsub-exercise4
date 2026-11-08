"""Run built service JARs against compose PostgreSQL/Redis and verify price invalidation."""
import concurrent.futures, json, os, pathlib, subprocess, tempfile, time, urllib.request, urllib.error

ROOT=pathlib.Path(__file__).resolve().parents[1]
PRODUCT_PORTS=(18087,18089)
PROMOTION_PORT=18088
processes=[]


def request(port,path,body=None,method=None):
    req=urllib.request.Request(f'http://localhost:{port}{path}',
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json'},method=method)
    try:
        with urllib.request.urlopen(req,timeout=10) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as error:
        return error.code,error.read().decode()


def redis(*args):
    return subprocess.check_output(['docker','compose','exec','-T','redis','redis-cli','--raw',*args],cwd=ROOT,text=True).strip()


def until(check,timeout=60):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            if check():return
        except (urllib.error.URLError,TimeoutError):pass
        time.sleep(.01)
    raise AssertionError('Timed out')


with tempfile.TemporaryDirectory(prefix='promotion-pubsub-') as temp:
    try:
        java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else 'java'
        for name,port in [('promotion-service',PROMOTION_PORT),('product-service',PRODUCT_PORTS[0]),('product-service',PRODUCT_PORTS[1])]:
            with open(pathlib.Path(temp)/f'{port}.log','w') as log:
                processes.append(subprocess.Popen([java,'-jar',str(ROOT/name/'build/libs'/f'{name}-1.0.0.jar')],
                    cwd=ROOT,env=dict(os.environ,SERVER_PORT=str(port)),stdout=log,stderr=subprocess.STDOUT))
            until(lambda:request(port,'/api/products/0')[0]==404)
        until(lambda:int(redis('PUBSUB','NUMSUB','promotion-updates').splitlines()[-1])>=2)
        code,target=request(PROMOTION_PORT,'/api/products',{'name':'Sale phone','price':100})
        assert code==201
        code,other=request(PROMOTION_PORT,'/api/products',{'name':'Unaffected product','price':200})
        assert code==201
        target_id=target['id'];other_id=other['id']
        for port in PRODUCT_PORTS:
            assert request(port,f'/api/products/{target_id}')[1]['price']==100
            assert request(port,f'/api/products/{other_id}')[1]['price']==200
        assert redis('EXISTS',f'products::{target_id}')=='1'
        latencies=[]
        for price in (90,80,70,60,50):
            start=time.perf_counter()
            code,result=request(PROMOTION_PORT,f'/api/promotions/products/{target_id}/price',{'price':price},'PUT')
            assert code==200 and result['subscribers']>=2,(code,result)
            until(lambda:redis('EXISTS',f'products::{target_id}')=='0',timeout=3)
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results=list(pool.map(lambda port:request(port,f'/api/products/{target_id}'),PRODUCT_PORTS))
            assert all(code==200 and body['price']==price for code,body in results),results
            elapsed=(time.perf_counter()-start)*1000
            assert elapsed<1000,f'Price refresh took {elapsed:.2f}ms'
            latencies.append(elapsed)
            assert redis('EXISTS',f'products::{other_id}')=='1','Evicted unrelated product'
        for port in PRODUCT_PORTS:
            assert f'Evicted product cache id={target_id}' in (pathlib.Path(temp)/f'{port}.log').read_text()
        assert request(PROMOTION_PORT,f'/api/promotions/products/{target_id}/price',{'price':-1},'PUT')[0]==400
        assert request(PROMOTION_PORT,'/api/promotions/products/0/price',{'price':10},'PUT')[0]==404
        print(f'PASS: two subscribers received updates; five price changes visible on both instances; max={max(latencies):.2f} ms',flush=True)
        print('PASS: old cache deleted, unrelated cache retained, invalid price rejected, missing product returns 404',flush=True)
        redis('DEL',f'products::{target_id}',f'products::{other_id}')
    except Exception:
        for log in pathlib.Path(temp).glob('*.log'):print(log.name,log.read_text()[-5000:])
        raise
    finally:
        for process in processes:process.terminate()
        for process in processes:
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
