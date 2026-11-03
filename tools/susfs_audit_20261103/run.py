#!/usr/bin/env python3
"""Controlled state transition and permission-ownership proofs against production C."""
import argparse,json,os,re,resource,subprocess,tempfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source-dir',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--sanitizers',action='store_true');a=p.parse_args()
resource.setrlimit(resource.RLIMIT_CORE,(0,0));here=Path(__file__).resolve().parent
def function(source,name):
    match=re.search(r'^(?:static )?[^\n]*\b'+re.escape(name)+r'\([^;{]*\)\n\{\n.*?^\}\n',source,re.M|re.S)
    if not match:raise RuntimeError('Missing '+name)
    return match.group()
mount=(a.source_dir/'kernel/sus_mount.c').read_text()
path=(a.source_dir/'kernel/sus_path.c').read_text()
mapping=(a.source_dir/'kernel/sus_map.c').read_text()
stat=(a.source_dir/'kernel/susfs_kstat.c').read_text()
syms=(a.source_dir/'kernel/susfs_hide_syms.c').read_text()
open_entry=(a.source_dir/'kernel/susfs_or_entry.c').read_text()
priority=(a.source_dir/'kernel/susfs_priority_filter.c').read_text()
mount_param=mount[mount.index('#define SUS_MOUNT_KEEP_MAX'):mount.index('/* All three mount-table hooks')]
mount_param+='\n'.join(function(mount,n) for n in ('sus_mount_keep_parse','sus_mount_keep_commit','sus_mount_keep_rescan','sus_mount_keep_command'))
start=mount.index('    {\n        unsigned long flags;',mount.index('int susfs_sus_mount_init('))
end=mount.index('\n    }',start)+len('\n    }')
mount_param+='\nstatic void audit_mount_init_defaults(void)\n'+mount[start:end]+'\n'
stat_fragment='#define SUS_KSTAT_MAX 32\n#define KSTAT_PATH_MAX 256\n'
stat_fragment+=stat[stat.index('struct sus_kstat_entry {'):stat.index('/* arm64 asm-generic struct stat offsets')]
stat_fragment+='\n'.join(function(stat,n) for n in ('susfs_kstat_gate_ok','susfs_kstat_table_empty','susfs_kstat_lookup'))
start=stat.index('struct kstat_call_counters {')
stat_fragment+=stat[start:stat.index('#define KSTAT_COUNTERS',start)]
start=stat.index('struct kstat_call_state {')
stat_fragment+=stat[start:stat.index('/* Rewrite the requested fields',start)]
stat_fragment+='\n'.join(re.findall(r'^#define STAT64_ST_.*$',stat,re.M))+'\n'
stat_fragment+=function(stat,'susfs_kstat_spoof_compat_statbuf')
cases={
'mount-control':'\n'.join(function(mount,n) for n in ('sus_mount_register_named_kprobe','sus_mount_register_named_kretprobe','sus_mount_register','sus_mount_unregister','susfs_sus_mount_supercall')),
'mode-ownership':'\n'.join(function(path,n) for n in ('sus_path_relax_mode','sus_path_restore_mode','sus_path_gate_ok')),
'map-range':mapping[mapping.index('static atomic_t n_gup_calls'):mapping.index('/* ---- /proc/<pid>/map_files/')],
'stat64-key':stat_fragment,
'late-control':syms[syms.index('#define HIDE_MODULES_MAX'):syms.index('/* ---- the two frontends ---- */')]+function(syms,'susfs_hide_syms_exit'),
'mount-parameter':mount_param,
'openat2-contract':open_entry[open_entry.index('struct susfs_open_how {'):open_entry.index('/* The five wrappers.')],
'priority-publication':function(priority,'prio_commit_locked'),
}
scenarios={'mount-control':['enable-disable'],
    'mode-ownership':['ordinary','foreign-chmod','foreign-setid-clear','exempt-shell-dac'],
    'map-range':['gup-single','pin-single','gup-cross-vma','pin-cross-vma'],
    'stat64-key':['low-inode','high-inode'],
    'late-control':['ordinary','after-teardown'],
    'mount-parameter':['live-setter','load-init'],
    'openat2-contract':['openat-cloexec','openat2-cloexec','compat-openat2-cloexec','resolve-beneath','resolve-in-root','oversize-how'],
    'priority-publication':['publication-lifetime']}
# Successful ordinary cases are independent controls for each API model. Every
# other baseline case MUST hit its precise invariant assertion, not merely die.
ordinary={('mode-ownership','ordinary'),('map-range','gup-single'),('map-range','pin-single'),
    ('stat64-key','low-inode'),('late-control','ordinary'),('mount-parameter','live-setter'),
    ('openat2-contract','openat-cloexec'),('priority-publication','publication-lifetime')}
assertions={'mount-control':'mount hiding enabled without its lifetime invalidators',
    'mode-ownership':'cleanup overwrote a foreign chmod/setid-clear',
    'map-range':'multi-page request admitted a later hidden mapping',
    'stat64-key':'stat64 failed to match the full 64-bit inode key',
    'late-control':'late sysfs command added a rule after path-layer teardown',
    'mount-parameter':'module initialization discarded the supplied hide_mounts parameter',
    'openat2-contract':'openat2 lost close-on-exec on its descriptor'}
def expected_assertion(label,case):
    if case=='exempt-shell-dac':return 'hiding granted DAC write access to an exempt shell caller'
    if case.startswith('resolve-'):return 'openat2 installed an unscoped absolute redirect descriptor'
    if case=='oversize-how':return 'openat2 accepted open_how larger than PAGE_SIZE'
    return assertions[label]
results=[]
valid=True
a.output.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='susfs-audit-proofs-') as temp:
    temp=Path(temp)
    def build(label,production,fixture=None,force_asan=False):
        header=temp/(label+'.h');header.write_text(production);exe=temp/label
        flags=['-O1','-fsanitize=address,undefined','-fno-omit-frame-pointer','-no-pie'] if a.sanitizers or force_asan else ['-O2']
        subprocess.run(['cc','-std=gnu11',*flags,'-g','-Wall','-Wextra','-Werror','-Wno-unused-function','-Wno-unused-variable','-Wno-unused-parameter','-Wno-sign-compare','-D__aarch64__','-pthread','-I',str(a.source_dir.resolve()),'-DPRODUCTION="'+str(header)+'"',str(here/((fixture or label)+'.c')),'-o',str(exe)],check=True)
        return exe
    def observe(exe,case):
        return subprocess.run([str(exe),case],capture_output=True,text=True,timeout=15,
            env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1'))
    def record(label,case,r,expected,passed):
        results.append(dict(group=label,case=case,expected=expected,matched=passed,exit_code=r.returncode,
            stdout=r.stdout.strip(),stderr=r.stderr.strip().replace(str(temp),'<temporary>').replace(str(here),'<fixtures>')))
    for label,production in cases.items():
        exe=build(label,production,force_asan=label=='priority-publication')
        for case in scenarios[label]:
            r=observe(exe,case)
            good=(label,case) in ordinary
            passed=r.returncode==0 if good else r.returncode==-6 and expected_assertion(label,case) in r.stderr
            record(label,case,r,'ordinary-success' if good else 'known-defect-assertion',passed);valid &= passed
            print(('MATCH ' if passed else 'UNEXPECTED ')+label+'/'+case+': '+('ordinary success' if good else 'known defect reproduced'),flush=True)

    # Detached mitigation controls establish that the new oracles distinguish
    # the diagnosed defect. They are not published kernel fixes or device tests.
    serialized=cases['mount-control'].replace('void susfs_sus_mount_supercall(', 'void audit_mount_supercall_body(',1)
    serialized+='\nvoid susfs_sus_mount_supercall(void **arg) {mutex_lock(&sus_mount_config_lock);audit_mount_supercall_body(arg);mutex_unlock(&sus_mount_config_lock);}\n'
    full_inode=cases['stat64-key'].replace('unsigned int ino = 0, dev = 0;', 'unsigned long long ino = 0; unsigned int dev = 0;',1)
    full_inode=full_inode.replace('statbuf + STAT64_ST_BROKEN_INO_OFF), sizeof(ino)', 'statbuf + STAT64_ST_INO_OFF), sizeof(ino)',1)
    cloexec=cases['openat2-contract'].replace('fd = or_get_unused_fd_flags_fn((unsigned)(flags & O_CLOEXEC));',
        'fd = or_get_unused_fd_flags_fn((unsigned)(((kind == SUSFS_OR_ENTRY_OPENAT2 || kind == SUSFS_OR_ENTRY_COMPAT_OPENAT2) ? how.flags : flags) & O_CLOEXEC));',1)
    sized=cases['openat2-contract'].replace('if (usize < sizeof(how))','if (usize > 4096 || usize < sizeof(how))',1)
    scoped=cases['openat2-contract'].replace('path_put(&path);\t/* no reference is kept anywhere:',
        'if (resolve) { path_put(&path); return -EOPNOTSUPP; }\n\tpath_put(&path);\t/* no reference is kept anywhere:',1)
    controls=[('serialized-mount',serialized,'mount-control',['enable-disable']),
        ('full-stat64-key',full_inode,'stat64-key',['high-inode']),
        ('retained-cloexec',cloexec,'openat2-contract',['openat2-cloexec','compat-openat2-cloexec']),
        ('bounded-open-how',sized,'openat2-contract',['oversize-how']),
        ('refused-unscoped-redirect',scoped,'openat2-contract',['resolve-beneath','resolve-in-root'])]
    for label,production,fixture,control_cases in controls:
        assert production!=cases[fixture],label+' did not change the intended source'
        exe=build(label,production,fixture)
        for case in control_cases:
            r=observe(exe,case);passed=r.returncode==0
            record('control/'+label,case,r,'ordinary-success',passed);valid &= passed
            print(('MATCH ' if passed else 'UNEXPECTED ')+'control/'+label+'/'+case,flush=True)
    before='if (next->count || next->automatic)\n        next = NULL;\n    mutex_unlock(&prio_config_lock);'
    assert before in cases['priority-publication']
    mutant=cases['priority-publication'].replace(before,'mutex_unlock(&prio_config_lock);\n    if (next->count || next->automatic)\n        next = NULL;',1)
    exe=build('unsafe-publication-control',mutant,'priority-publication',force_asan=True)
    r=observe(exe,'publication-lifetime')
    passed=r.returncode!=0 and 'AddressSanitizer: heap-use-after-free' in r.stderr
    record('control/unsafe-publication','publication-lifetime',r,'ASan-heap-use-after-free',passed);valid &= passed
    print(('MATCH ' if passed else 'UNEXPECTED ')+'control/unsafe-publication deterministic ASan detection',flush=True)
summary={'schema':1,'scope':'extracted shipping C with modeled kernel APIs; known defects are EXPECTED assertions',
    'sanitizers':a.sanitizers,'hardware_validated':False,'matched':valid,'results':results}
(a.output/'proofs.json').write_text(json.dumps(summary,indent=2)+'\n')
raise SystemExit(0 if valid else 1)
