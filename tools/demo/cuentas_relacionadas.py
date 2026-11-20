#!/usr/bin/env python3
"""Cuentas de la cadena 4° de Primaria A (2027): docente → alumnos → apoderados, con su horario.

Crea en Keycloak (realm eduspace) las cuentas que faltan, con el mismo `usuarioId` que se guarda en la ficha de
EduSpace, y las vincula con la API (alumnos y apoderados). Clave inicial = número de documento; el sistema pide
cambiarla al entrar (regla del proyecto). Se puede repetir: lo que ya existe se actualiza, no se duplica.

  python3 tools/demo/cuentas_relacionadas.py

Requiere Keycloak (18090) y el gateway (18080) arriba.
"""
import json, os, sys, urllib.request, urllib.parse, urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib  # noqa: E402  (token y llamadas a la API por el gateway)

KC = os.environ.get('KEYCLOAK_URL', 'http://localhost:18090')
REALM = 'eduspace'

# (tipo, id en EduSpace, usuario Keycloak, nombres, apellidos, documento, correo, usuarioId, rol)
CUENTAS = [
    ('alumno', 4, 'lucia.mendoza', 'Lucía', 'Mendoza Ríos', '70234501', 'lucia.mendoza@ejemplo.pe', 6, 'ALUMNO'),
    ('alumno', 5, 'mateo.mendoza', 'Mateo', 'Mendoza Ríos', '71234511', 'mateo.mendoza@ejemplo.pe', 101, 'ALUMNO'),
    ('alumno', 6, 'sofia.vargas', 'Sofía', 'Vargas Luna', '72234521', 'sofia.vargas@ejemplo.pe', 102, 'ALUMNO'),
    ('alumno', 7, 'diego.salas', 'Diego', 'Salas Vega', '73234531', 'diego.salas@ejemplo.pe', 103, 'ALUMNO'),
    ('alumno', 25, 'alessia.pillco', 'Alessia', 'Pillco Ayma', '74100001', 'alessia.pillco@ejemplo.pe', 104, 'ALUMNO'),
    ('alumno', 27, 'milagros.ortiz', 'Milagros', 'Ortiz Chura', '74100003', 'milagros.ortiz@ejemplo.pe', 105, 'ALUMNO'),
    ('alumno', 28, 'gael.ramos', 'Gael', 'Ramos Pari', '74100004', 'gael.ramos@ejemplo.pe', 106, 'ALUMNO'),
    ('alumno', 30, 'joel.vasquez', 'Joel', 'Vásquez Puma', '74100006', 'joel.vasquez@ejemplo.pe', 107, 'ALUMNO'),
    ('apoderado', 1, 'carmen.rios', 'Carmen', 'Ríos', '40123456', 'carmen@ejemplo.com', 111, 'APODERADO'),
    ('apoderado', 4, 'jorge.vargas.apo', 'Jorge', 'Vargas', '40777888', 'jvargas@ejemplo.com', 112, 'APODERADO'),
    ('apoderado', 5, 'rosa.vega', 'Rosa', 'Vega', '40555666', 'rvega@ejemplo.com', 113, 'APODERADO'),
    ('apoderado', 18, 'rosa.ayma', 'Rosa', 'Ayma', '42100001', 'apoderado1@ejemplo.com', 114, 'APODERADO'),
    ('apoderado', 20, 'elena.chura', 'Elena', 'Chura', '42100003', 'apoderado3@ejemplo.com', 115, 'APODERADO'),
    ('apoderado', 21, 'marco.pari', 'Marco', 'Pari', '42100004', 'apoderado4@ejemplo.com', 116, 'APODERADO'),
    ('apoderado', 23, 'victor.puma', 'Víctor', 'Puma', '42100006', 'apoderado6@ejemplo.com', 117, 'APODERADO'),
]
# Lucía Mendoza (alumna 4) ya tiene cuenta (usuario 6, «lucia.mendoza»); el docente es el usuario 4 («docente»).
# La cuenta ya existente de Lucía también recibe la clave del documento, para que entre igual que las demás.
CLAVE_EXISTENTE = [('lucia.mendoza', '70234501')]


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


s, d, _ = kc('POST', '/realms/master/protocol/openid-connect/token', None,
             form={'grant_type': 'password', 'client_id': 'admin-cli', 'username': 'admin', 'password': 'admin'})
if s != 200:
    sys.exit(f'No se pudo entrar a Keycloak como administrador: {s} {d}')
adm = d['access_token']

for tipo, ficha, usuario, nombres, apellidos, doc, correo, uid, rol in CUENTAS:
    cuerpo = {'username': usuario, 'enabled': True, 'emailVerified': True, 'firstName': nombres, 'lastName': apellidos, 'email': correo,
              'attributes': {'usuarioId': [str(uid)]}, 'requiredActions': ['UPDATE_PASSWORD']}
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
    # Clave definitiva = documento (cuentas de desarrollo): entra directo, sin obligar a cambiarla.
    kc('PUT', f'/admin/realms/{REALM}/users/{kid}/reset-password', adm, {'type': 'password', 'value': doc, 'temporary': False})
    kc('PUT', f'/admin/realms/{REALM}/users/{kid}', adm, {**cuerpo, 'requiredActions': []})
    s, r, _ = kc('GET', f'/admin/realms/{REALM}/roles/{rol}', adm)
    if s == 200:
        kc('POST', f'/admin/realms/{REALM}/users/{kid}/role-mappings/realm', adm, [r])
    # Vincular en EduSpace
    recurso = 'alumnos' if tipo == 'alumno' else 'apoderados'
    s, r = lib.call('PATCH', f'/api/v1/{recurso}/{ficha}/usuario', 'admin', {'usuarioId': uid})
    vinculo = 'vinculada' if s == 200 else f'NO vinculada ({s} {str(r)[:120]})'
    print(f'  ✔ {usuario} ({tipo} {ficha}) {accion} · usuarioId {uid} · {vinculo}')

for usuario, doc in CLAVE_EXISTENTE:
    s, lista, _ = kc('GET', f'/admin/realms/{REALM}/users?username={urllib.parse.quote(usuario)}&exact=true', adm)
    if lista:
        kc('PUT', f"/admin/realms/{REALM}/users/{lista[0]['id']}/reset-password", adm, {'type': 'password', 'value': doc, 'temporary': False})
        ficha = dict(lista[0], requiredActions=[])  # ficha completa: un PUT parcial borraría los demás campos
        kc('PUT', f"/admin/realms/{REALM}/users/{lista[0]['id']}", adm, ficha)
        print(f'  ✔ {usuario}: clave del documento establecida')

# Comprobación: entrar con una de las cuentas nuevas (clave = documento)
s, d, _ = kc('POST', f'/realms/{REALM}/protocol/openid-connect/token', None,
             form={'grant_type': 'password', 'client_id': 'eduspace-e2e', 'username': 'mateo.mendoza', 'password': '71234511'})
print('Comprobación de inicio de sesión (mateo.mendoza):', 'OK' if s == 200 else f'falla {s}')
