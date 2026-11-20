#!/usr/bin/env python3
"""Docentes con cursos que puede dictar, pero SIN sección asignada (datos de demostración).

Sirven para probar el armado automático y las asignaciones: aparecen como «apto» en sus cursos y no tienen
ninguna sección. Se puede repetir: los que ya existen (por documento) no se duplican.

  python3 tools/demo/docentes_disponibles.py
"""
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import call, Resultado  # noqa: E402

R = Resultado()

# (nombres, apellidos, especialidad, cursos aptos)
DOCENTES = [
    ('Rubén', 'Huaman Ccori', 'Matemática', ['Matemática', 'Ciencia y Tecnología']),
    ('Silvia', 'Paredes Lazo', 'Comunicación', ['Comunicación', 'Personal Social']),
    ('Jaime', 'Vilca Quispe', 'Arte y Cultura', ['Arte y Cultura', 'Educación Religiosa']),
    ('Norma', 'Ayala Ramos', 'Educación Física', ['Educación Física', 'Tutoría']),
    ('Pablo', 'Cruz Mamani', 'Inglés', ['Inglés']),
    ('Elvira', 'Suca Huanca', 'Matemática', ['Matemática']),
    ('Gustavo', 'Chura Torres', 'Comunicación', ['Comunicación', 'Inglés']),
    ('Teodora', 'Poma Vargas', 'Ciencia y Tecnología', ['Ciencia y Tecnología', 'Personal Social']),
    ('Basilio', 'Ccama Lazo', 'Educación Religiosa', ['Educación Religiosa', 'Tutoría']),
    ('Carla', 'Ticona Flores', 'Arte y Cultura', ['Arte y Cultura', 'Educación Física']),
]

area = next((a for a in call('GET', '/api/v1/planificacion/areas', 'admin')[1] if a.get('codigo') == 'DOCENCIA'), None)
if not area:
    sys.exit('No existe el área DOCENCIA en planificación')
cursos = {c['nombre']: c['id'] for c in call('GET', '/api/v1/planificacion/cursos', 'admin')[1]}
existentes = {d['numeroDocumento'] for d in call('GET', '/api/v1/planificacion/docentes?page=0&size=200', 'admin')[1]['contenido']}

for i, (nom, ape, esp, materias) in enumerate(DOCENTES, start=1):
    dni = f'73{i:06d}'
    if dni in existentes:
        continue
    ids = [cursos[m] for m in materias if m in cursos]
    R.ok(f'docente {nom} {ape} · {", ".join(materias)}', call('POST', '/api/v1/planificacion/docentes', 'secretaria', {
        'tipoDocumento': 'DNI', 'numeroDocumento': dni, 'nombres': nom, 'apellidos': ape,
        'email': f'docente.disp.{i}@eduspace.test', 'especialidad': esp, 'areaId': area['id'], 'cursoIds': ids}))

raise SystemExit(R.fin())
