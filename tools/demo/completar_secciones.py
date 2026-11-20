#!/usr/bin/env python3
"""Deja todas las secciones del año activo listas para funcionar (datos de demostración).

Etapas (se pueden repetir; lo que ya existe no se duplica):
  malla      malla curricular de cada grado (cursos y horas semanales) según su nivel
  horarios   armado automático de horarios con asignación de docentes, y publicación
  tutores    un docente tutor por sección
  alumnos    alumnos con apoderado y matrícula en cada sección (pre-matrícula)
  asistencia hojas de asistencia de ayer (cerrada) y hoy (abierta) de cada sección
  todo       todas las anteriores, en ese orden

  python3 tools/demo/completar_secciones.py malla
"""
import datetime, json, sys, time, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import call, Resultado  # noqa: E402
from semilla import asegurar_expediente  # noqa: E402  (sube partida, DNI y ficha SIAGIE de cada alumno)

R = Resultado()
HOY = datetime.date.today()

# Horas semanales por nivel: (curso, horas)
MALLA = {
    'Inicial': [('Matemática', 4), ('Comunicación', 4), ('Arte y Cultura', 2), ('Educación Física', 2), ('Tutoría', 1)],
    'Primaria': [('Matemática', 5), ('Comunicación', 5), ('Ciencia y Tecnología', 3), ('Personal Social', 2), ('Arte y Cultura', 2),
                 ('Educación Física', 2), ('Educación Religiosa', 2), ('Inglés', 2), ('Tutoría', 1)],
    'Secundaria': [('Matemática', 5), ('Comunicación', 5), ('Ciencia y Tecnología', 4), ('Personal Social', 2), ('Arte y Cultura', 2),
                   ('Educación Física', 2), ('Educación Religiosa', 2), ('Inglés', 2), ('Tutoría', 1)],   # 25 h = los espacios de la semana
}
ASIGNATURAS = ['Matemática', 'Comunicación', 'Ciencia y Tecnología', 'Personal Social', 'Arte y Cultura', 'Educación Física',
               'Educación Religiosa', 'Inglés', 'Tutoría']
NOMBRES = [('Lucía', 'Huamán'), ('Mateo', 'Pacheco'), ('Sofía', 'Ccori'), ('Diego', 'Mamani'), ('Valentina', 'Torres'), ('Santiago', 'Ticona'),
           ('Camila', 'Aguirre'), ('Joaquín', 'Lazo'), ('Renata', 'Coaquira'), ('Thiago', 'Quispe'), ('Mía', 'Vilca'), ('Bruno', 'Condori'),
           ('Antonella', 'Ramos'), ('Nicolás', 'Pérez'), ('Emilia', 'Lucana'), ('Gabriel', 'Ccama')]
APELLIDOS_APO = ['Huanca', 'Ramos', 'Quispe', 'Flores', 'Mamani', 'Torres', 'Vargas', 'Cruz']
ALUMNOS_POR_SECCION = 8
DOC_BASE = 81000000   # documentos ficticios de alumnos y apoderados (no coinciden con los de otras semillas)


def anio_activo():
    st, anio = call('GET', '/api/v1/planificacion/anios/activo', 'admin')
    if st != 200:
        sys.exit(f'No hay año activo: {st} {anio}')
    return anio


def secciones(anio_id):
    return call('GET', f'/api/v1/planificacion/secciones?anioId={anio_id}', 'admin')[1]


def cursos_por_nombre():
    return {c['nombre']: c for c in call('GET', '/api/v1/planificacion/cursos', 'admin')[1]}


def etapa_malla(anio):
    cursos = cursos_por_nombre()
    grados = call('GET', '/api/v1/planificacion/grados', 'admin')[1]
    for g in grados:
        if g.get('activo') is False:
            continue
        nivel = g.get('nivelNombre')
        if nivel not in MALLA:
            continue
        actual = call('GET', f"/api/v1/planificacion/malla/grado/{g['id']}", 'admin')[1]
        filas = {c['cursoId']: c for c in actual.get('cursos', [])} if isinstance(actual, dict) else {}
        for nombre, horas in MALLA[nivel]:
            if nombre not in cursos:
                continue
            if cursos[nombre]['id'] in filas:
                fila = filas[cursos[nombre]['id']]
                if fila.get('horasSemanales') != horas and fila.get('id'):  # corrige horas que ya estaban cargadas
                    R.ok(f"malla {g['nombre']}: {nombre} → {horas} h", call('PUT', f"/api/v1/planificacion/malla/{fila['id']}", 'admin',
                                                                            {'gradoId': g['id'], 'cursoId': cursos[nombre]['id'], 'horasSemanales': horas}))
                continue
            R.ok(f"malla {g['nombre']}: {nombre} {horas} h", call('POST', '/api/v1/planificacion/malla', 'admin',
                                                                  {'gradoId': g['id'], 'cursoId': cursos[nombre]['id'], 'horasSemanales': horas}))


def etapa_horarios(anio):
    ids = [s['id'] for s in secciones(anio['id']) if s.get('activo', True)]
    st, r = call('POST', '/api/v1/planificacion/horarios/generar', 'admin',
                 {'anioId': anio['id'], 'seccionIds': ids, 'reemplazar': False, 'asignarDocentes': True, 'simular': False})
    R.ok(f'armado automático de horarios ({len(ids)} secciones)', (st, r))
    if isinstance(r, dict):
        print('   ', {k: v for k, v in r.items() if not isinstance(v, (list, dict))})
    for sid in ids:
        R.ok(f'publicar horario de la sección {sid}', call('POST', f'/api/v1/planificacion/horarios/publicar/seccion/{sid}', 'admin'), esperado=(200, 201, 409))


def etapa_tutores(anio):
    docentes = [d for d in call('GET', '/api/v1/planificacion/docentes?page=0&size=200', 'admin')[1]['contenido'] if d.get('estado') == 'ACTIVO']
    if not docentes:
        print('   (no hay docentes activos)')
        return
    for i, s in enumerate(secciones(anio['id'])):
        if s.get('tutorDocenteId'):
            continue
        doc = docentes[i % len(docentes)]
        R.ok(f"tutor de {s['gradoNombre']} {s['nombre']}: {doc['nombres']} {doc['apellidos']}",
             call('PUT', f"/api/v1/planificacion/secciones/{s['id']}", 'admin', {'anioId': s['anioId'], 'gradoId': s['gradoId'], 'aulaId': s.get('aulaId'),
                                                                               'nombre': s['nombre'], 'turno': s['turno'], 'capacidad': s['capacidad'],
                                                                               'tutorDocenteId': doc['id'], 'activo': True}))


def todos_los_alumnos():
    """Documento → id de todos los alumnos (recorre todas las páginas)."""
    mapa, pagina = {}, 0
    while True:
        p = call('GET', f'/api/v1/alumnos?page={pagina}&size=100', 'secretaria')[1]
        contenido = p.get('contenido', []) if isinstance(p, dict) else []
        for a in contenido:
            mapa[a['numeroDocumento']] = a['id']
        if not contenido or pagina + 1 >= p.get('totalPaginas', 1):
            return mapa
        pagina += 1


def buscar_alumno_por_documento(doc):
    return todos_los_alumnos().get(doc)


def etapa_alumnos(anio):
    existentes = todos_los_alumnos()
    matriculas = call('GET', f"/api/v1/matriculas?anioId={anio['id']}&page=0&size=500", 'secretaria')[1]['contenido']
    con_matricula = {m['alumnoId'] for m in matriculas if m['estado'] in ('PRE_MATRICULA', 'POR_APROBAR', 'MATRICULADO')}
    n = 0
    for s in secciones(anio['id']):
        ya = sum(1 for m in matriculas if m['seccionId'] == s['id'] and m['estado'] in ('PRE_MATRICULA', 'POR_APROBAR', 'MATRICULADO'))
        for k in range(max(0, ALUMNOS_POR_SECCION - ya)):
            n += 1
            doc_a = str(DOC_BASE + n * 2)
            doc_p = str(DOC_BASE + n * 2 + 1)
            nom, ape = NOMBRES[n % len(NOMBRES)]
            ap1 = APELLIDOS_APO[n % len(APELLIDOS_APO)]
            ap2 = APELLIDOS_APO[(n + 3) % len(APELLIDOS_APO)]
            edad = 4 if 'años' in s['gradoNombre'] else (int(''.join(c for c in s['gradoNombre'] if c.isdigit()) or 1) + (5 if 'Primaria' in s['gradoNombre'] else 11))
            nacimiento = f'{int(anio["nombre"]) - edad}-{(n % 12) + 1:02d}-{(n % 27) + 1:02d}'
            vinculo = {'apoderado': {'numeroDocumento': doc_p, 'nombres': f'Familiar {n}', 'apellidos': f'{ap1} {ap2}', 'telefono': f'9{n:08d}'[:9],
                                     'email': f'familia.{doc_p}@ejemplo.pe'}, 'parentesco': 'MADRE' if n % 2 else 'PADRE', 'esPrincipal': True,
                       'esResponsablePago': True, 'viveConAlumno': True, 'puedeRecoger': True}
            aid = existentes.get(doc_a)
            if aid:
                pass
            else:
                st, a = call('POST', '/api/v1/alumnos', 'secretaria', {'numeroDocumento': doc_a, 'nombres': nom, 'apellidoPaterno': ap1, 'apellidoMaterno': ap2,
                                                                      'fechaNacimiento': nacimiento, 'sexo': 'F' if n % 2 else 'M', 'distrito': 'Miraflores',
                                                                      'apoderados': [vinculo]})
                if st != 201:
                    R.ok(f'alumno {nom} {ap1} ({s["nombre"]})', (st, a))
                    continue
                aid = a['id']
            if aid in con_matricula:
                continue
            asegurar_expediente(aid)  # la matrícula exige el expediente completo (partida, DNI y ficha SIAGIE)
            R.ok(f"matrícula {nom} {ap1} → {s['gradoNombre']} {s['nombre']}", call('POST', '/api/v1/matriculas', 'secretaria',
                                                                             {'alumnoId': aid, 'seccionId': s['id'], 'tipo': 'NUEVA'}))


def etapa_asistencia(anio):
    dias = [('ayer', HOY - datetime.timedelta(days=1), True), ('hoy', HOY, False)]
    for s in secciones(anio['id']):
        st_al, lista_al = call('GET', f"/api/v1/matriculas/seccion/{s['id']}/alumnos", 'secretaria')
        alumnos = [x['alumnoId'] for x in lista_al] if st_al == 200 and isinstance(lista_al, list) else []
        for etiqueta, dia, cerrar in dias:
            if dia.weekday() >= 5:
                continue
            st, hoja = call('POST', '/api/v1/asistencia/sesiones', 'admin', {'seccionId': s['id'], 'fecha': dia.isoformat(), 'tipo': 'DIA'})
            if st not in (200, 201) or not isinstance(hoja, dict):
                R.ok(f"asistencia {s['nombre']} {etiqueta}", (st, hoja))
                continue
            if hoja.get('estado') == 'CERRADA':
                continue
            if alumnos:
                items = [{'alumnoId': x, 'estado': 'PRESENTE'} for x in alumnos]
                call('PUT', f"/api/v1/asistencia/sesiones/{hoja['id']}/registro", 'admin', {'items': items})
            if cerrar:
                R.ok(f"asistencia {s['gradoNombre']} {s['nombre']} {etiqueta}: cerrar", call('PATCH', f"/api/v1/asistencia/sesiones/{hoja['id']}/cerrar", 'admin', {}))


def etapa_docentes():
    """Un docente por asignatura (especialidad = nombre de la asignatura): hacen falta para secundaria, que no tenía."""
    existentes = {d['numeroDocumento'] for d in call('GET', '/api/v1/planificacion/docentes?page=0&size=200', 'admin')[1]['contenido']}
    # Las asignaturas troncales (5 y 4 h por semana en secundaria) llevan dos docentes extra.
    materias = ASIGNATURAS + ['Matemática', 'Comunicación', 'Ciencia y Tecnología'] * 2
    nombres = ['Renzo', 'Patricia', 'Héctor', 'Lourdes', 'Oscar', 'Gabriela', 'Fernando', 'Mirtha', 'Víctor', 'Rocío', 'Julio', 'Elsa', 'Marco', 'Nilda', 'Iván']
    apellidos = ['Quispe Ramos', 'Huanca Ccori', 'Ayma Pari', 'Mamani Vilca', 'Ticona Ríos', 'Coaquira Lazo', 'Pacheco Soto', 'Lucana Pérez',
                 'Condori Flores', 'Vilca Huarca', 'Ccama Ramos', 'Torres Quispe', 'Aguirre Mamani', 'Peralta Ccori', 'Sosa Chura']
    for i, materia in enumerate(materias, start=1):
        dni = f'72{i:06d}'
        if dni in existentes:
            continue
        R.ok(f'docente de {materia} ({i})', call('POST', '/api/v1/planificacion/docentes', 'secretaria',
                                               {'tipoDocumento': 'DNI', 'numeroDocumento': dni, 'nombres': nombres[(i - 1) % len(nombres)],
                                                'apellidos': apellidos[(i - 1) % len(apellidos)], 'email': f'docente.{i}@eduspace.test', 'especialidad': materia}))


if __name__ == '__main__':
    etapa = sys.argv[1] if len(sys.argv) > 1 else 'malla'
    anio = anio_activo()
    print(f"Año activo: {anio['nombre']} (id {anio['id']})")
    if etapa in ('malla', 'todo'):
        etapa_malla(anio)
    if etapa in ('docentes', 'todo'):
        etapa_docentes()
    if etapa in ('horarios', 'todo'):
        etapa_horarios(anio)
    if etapa in ('tutores', 'todo'):
        etapa_tutores(anio)
    if etapa in ('alumnos', 'todo'):
        etapa_alumnos(anio)
    if etapa in ('asistencia', 'todo'):
        etapa_asistencia(anio)
    raise SystemExit(R.fin())
