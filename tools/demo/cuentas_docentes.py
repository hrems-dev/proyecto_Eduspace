#!/usr/bin/env python3
"""Cuentas de acceso para los docentes que no tienen una (datos de demostración).

Para cada docente de planificación sin `usuario_id`: crea (o actualiza) su usuario en Keycloak con rol DOCENTE,
clave = número de documento (definitiva, sin cambio obligatorio) y `usuarioId` = 200 + id del docente, y lo vincula
con PATCH /api/v1/planificacion/docentes/{id}/usuario. Se puede repetir.

  python3 tools/demo/cuentas_docentes.py
"""
import json, os, re, sys, unicodedata, urllib.parse, urllib.request, urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib  # noqa: E402

KC = os.environ.get('KEYCLOAK_URL', 'http://localhost:18090')
REALM = 'eduspace'


def kc(metodo, ruta, tok, cuerpo=None, form=None):
    h, datos = {}, None
    if form is not None:
        datos, h['Content-Type'] = urllib.parse.urlencode(form).encode(), 'application/x-www-form-urlencoded'
    elif cuerpo is not None:
        datos, h['Content-Type'] = json.dumps(cuerpo).encode(), 'application/json'
    if tok:
        h['Authorization'] = 'Bearer ' + tok
    r = urllib.request.Request(KC + ruta, data=datos, headers=h, method=metodo)
    try:
        with urllib.request.urlopen(r, timeout=30) as x:
            t = x.read()
            return x.status, (json.loads(t) if t[:1] in (b'{', b'[') else None), x.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300], e.headers


def sin_acentos(t):
    return ''.join(c for c in unicodedata.normalize('NFD', t) if unicodedata.category(c) != 'Mn')


def usuario_de(nombres, apellidos, docente_id):
    base = re.sub(r'[^a-z]', '', sin_acentos(nombres.split()[0].lower())) + '.' + re.sub(r'[^a-z]', '', sin_acentos(apellidos.split()[0].lower()))
    return base


s, d, _ = kc('POST', '/realms/master/protocol/openid-connect/token', None, form={'grant_type': 'password', 'client_id': 'admin-cli', 'username': 'admin', 'password': 'admin'})
if s != 200:
    sys.exit(f'No se pudo entrar a Keycloak como administrador: {s} {d}')
adm = d['access_token']

docentes = [x for x in lib.call('GET', '/api/v1/planificacion/docentes?page=0&size=200', 'admin')[1]['contenido'] if not x.get('usuarioId')]
print(f'Docentes sin cuenta: {len(docentes)}')
usados = set()
for doc in docentes:
    usuario = usuario_de(doc['nombres'], doc['apellidos'], doc['id'])
    if usuario in usados:
        usuario = f"{usuario}{doc['id']}"
    usados.add(usuario)
    uid = 200 + doc['id']
    cuerpo = {'username': usuario, 'enabled': True, 'emailVerified': True, 'firstName': doc['nombres'], 'lastName': doc['apellidos'],
              'email': doc.get('email') or f'{usuario}@eduspace.test', 'attributes': {'usuarioId': [str(uid)]}, 'requiredActions': []}
    s, lista, _ = kc('GET', f'/admin/realms/{REALM}/users?username={urllib.parse.quote(usuario)}&exact=true', adm)
    if lista:
        kid = lista[0]['id']
        kc('PUT', f'/admin/realms/{REALM}/users/{kid}', adm, cuerpo)
        accion = 'actualizada'
    else:
        s, r, h = kc('POST', f'/admin/realms/{REALM}/users', adm, cuerpo)
        if s != 201:
            print(f'  ✘ {usuario}: {s} {r}')
            continue
        kid = h['Location'].rsplit('/', 1)[1]
        accion = 'creada'
    kc('PUT', f'/admin/realms/{REALM}/users/{kid}/reset-password', adm, {'type': 'password', 'value': doc['numeroDocumento'], 'temporary': False})
    s, r, _ = kc('GET', f'/admin/realms/{REALM}/roles/DOCENTE', adm)
    if s == 200:
        kc('POST', f'/admin/realms/{REALM}/users/{kid}/role-mappings/realm', adm, [r])
    s, r = lib.call('PATCH', f"/api/v1/planificacion/docentes/{doc['id']}/usuario", 'admin', {'usuarioId': uid})
    vinculo = 'vinculado' if s == 200 else f'NO vinculado ({s} {str(r)[:100]})'
    print(f"  ✔ {usuario} ({doc['nombres']} {doc['apellidos']}) {accion} · usuarioId {uid} · {vinculo}")
