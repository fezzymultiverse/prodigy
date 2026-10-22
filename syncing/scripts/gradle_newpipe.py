# S.O.I: wires NewPipe Extractor + desugaring + OkHttp into the generated Capacitor project (idempotent)
import sys, re, os
top, app = sys.argv[1], sys.argv[2]
# V59: NewPipeExtractor version = $NEWPIPE_VERSION, else scripts/newpipe.version (kept fresh by .github/workflows/newpipe-update.yml), else v0.26.5
_vf = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'newpipe.version')
NP = (os.environ.get('NEWPIPE_VERSION') or (open(_vf).read().strip() if os.path.exists(_vf) else '') or 'v0.26.5').strip()
assert re.fullmatch(r'v?[0-9][0-9A-Za-z.\-]*', NP), 'bad NewPipe version: ' + NP
print('NewPipeExtractor', NP)
t = open(top, encoding='utf-8').read()
if 'jitpack.io' not in t:
    t = t.replace('mavenCentral()', "mavenCentral()\n        maven { url 'https://jitpack.io' }")
    assert 'jitpack.io' in t, 'no mavenCentral() in android/build.gradle'
    open(top, 'w', encoding='utf-8').write(t); print('jitpack added')
a = open(app, encoding='utf-8').read()
if 'NewPipeExtractor' not in a:
    ANDROID = """android {
    compileOptions {
        coreLibraryDesugaringEnabled true
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    packaging { resources { excludes += ['META-INF/DEPENDENCIES','META-INF/LICENSE*','META-INF/NOTICE*','META-INF/INDEX.LIST'] } }"""
    DEPS = """dependencies {
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs_nio:2.1.5'
    implementation 'com.github.TeamNewPipe:NewPipeExtractor:__NP__'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'androidx.webkit:webkit:1.11.0'"""
    DEPS = DEPS.replace('__NP__', NP)
    a, n1 = re.subn(r'\bandroid\s*\{', lambda m: ANDROID, a, count=1)
    a, n2 = re.subn(r'\bdependencies\s*\{', lambda m: DEPS, a, count=1)
    assert n1 and n2, 'gradle blocks not found'
    open(app, 'w', encoding='utf-8').write(a); print('gradle patched')

a = open(app, encoding='utf-8').read()
if 'androidx.biometric' not in a:
    a = a.replace("implementation 'androidx.webkit:webkit:1.11.0'", "implementation 'androidx.webkit:webkit:1.11.0'\n    implementation 'androidx.biometric:biometric:1.1.0'", 1)
    assert 'androidx.biometric' in a, 'biometric dependency not added'
    open(app, 'w', encoding='utf-8').write(a); print('biometric added')
