# Hallazgos de la prueba en Chrome (localhost:4200)

Fecha: 2026-11-19. Usuario de prueba: `admin` (seed local).

## Recorrido realizado

| Módulo | Ruta | Resultado |
|---|---|---|
| Planificación · Panel de aulas | `/planificacion/panel` | Carga. 25 secciones. Detalle de "3 años · A" muestra 0 alumnos, 0 docentes, "Sin tutor". |
| Planificación · Cursos y docentes | `/planificacion/cursos-docentes` | Carga. 9 cursos, 16 docentes activos, 15 sin cursos. |
| Planificación · Asignaciones | `/planificacion/asignaciones` | Carga. 4° de Primaria A con 3 asignaciones a Paredes, Jorge. |
| Planificación · Armado automático | `/planificacion/horarios-auto` | Carga. Solo 4° de Primaria tiene cursos (3 cursos, 13 h). |
| Notificaciones · Bandeja | `/notificaciones` | Carga. Sin notificaciones. |
| Notificaciones · Envío | `/notificaciones/envio` | Carga. No se envió ningún aviso. |
| Alumnos · Listado | `/alumnos` | Carga. |
| Alumnos · Matrícula (acción) | `/matricula/asistente?alumnoId=29` | Pre-matrícula **MAT-2027-000026** creada para Soto Mamani, Fernanda (1° de Primaria · Sección A, pago mensual). |
| Matrículas · Por aprobar | `/matriculas/aprobaciones` | Carga. 3 matrículas pagadas pendientes de aprobar. |
| Alumnos · Expediente | `/alumnos/29` | Carga. Muestra "Datos personales incompletos" y "Sin cuenta". |

No se encontraron errores de consola durante el recorrido.

## Observaciones (pendientes de revisar)

1. **Sección sin alumnos ni docentes.** El panel de aulas muestra las 25 secciones con 0/30 y "Sin tutor". Puede ser un dato incompleto del seed y no un bug. Confirmar con la semilla.
2. **Pre-matrícula recién creada no aparece como "Por aprobar".** Se creó con estado pre-matrícula y debe pagarse en Caja antes de aprobarse. Se verificó que el flujo lo indica así; no se probó el pago.
3. **Destinatarios de avisos.** En `/notificaciones/envio`, "Docentes elegidos: 16" se resume como "Se enviará a 1 usuario(s)" con aviso de que 15 no tienen cuenta. Revisar si el texto es claro para el administrador.
4. **Rutas con prefijo `/portal`.** `/portal/planificacion/panel` redirige a la portada pública en vez de mostrar 404 o llevar al panel. Las rutas reales no llevan ese prefijo. Revisar si conviene un redirect.
5. **Matrícula sin grado elegido por dato del alumno.** El asistente no sugiere grado según la fecha de nacimiento (Soto Mamani nació 2019-01-17). Se eligió 1° de Primaria manualmente. Revisar si debería sugerirse.

## No probado todavía

- Enviar un aviso (acción hacia usuarios reales del seed; requiere confirmación).
- Aprobar una matrícula de "Por aprobar".
- Cambiar estado de alumno, crear cuenta de acceso, cupos y cuentas por alumno.

## Ronda 2: cuentas de acceso (alumna, docente, apoderados)

Acceso probado desde `docs/datos-de-prueba-alumnos.md` (sección 6) y `infra/keycloak/README.md`.

### H-1. Cuenta restaurada no entra (falla confirmada)
- **Qué pasa:** `lucia.mendoza` aparece "Aún no cambia su clave". Login con DNI `70234501` y con `Expo12345` → "Usuario o clave incorrectos". "Restaurar" en Personal → Cuentas de acceso confirma "Clave inicial: su documento (70234501)", y el login sigue fallando.
- **Evidencia:** auth-ms `localhost:8090/api/v1/auth/login` acepta `lucia.mendoza` / `70234501` (devuelve token). El frontend (modo `keycloak-form`) valida contra Keycloak `localhost:18090`, donde la misma clave devuelve `Invalid user credentials` (con `Expo12345` también).
- **Causa probable (no verificada del todo):** las altas y restauraciones de cuentas escriben la clave en auth-ms, pero en modo Keycloak no la actualizan en Keycloak. Falta confirmar el usuario en Keycloak (la consulta por la API de administración fue bloqueada y no se insistió).
- **Repro:** Personal → Cuentas de acceso → buscar "Mendoza" → Restaurar Lucía → login `lucia.mendoza` / DNI.

### H-2. Lista de cuentas no coincide con la documentación
- Cuentas de acceso muestra 221 alumnos, 0 activas, 2 "aún no cambian su clave", 219 "sin cuenta". La documentación describe 33 alumnos y varias cuentas creadas.
- `mateo.mendoza` figura "Sin cuenta" en auth-ms, pero en Keycloak existe (`Account is not fully set up` con DNI `71234511`). Desincronización entre auth-ms y Keycloak.
- Botón "Crear 219 cuenta(s) faltantes" en la cabecera: acción masiva, no ejecutada.

### H-3. Docente `docente` pide cambiar clave en el primer ingreso
- Login UI con `docente` / `docente123` (clave de `lib.py` y de la suite e2e) → redirige a `/cambiar-clave` ("Tu clave es temporal").
- Keycloak da token directo con `docente123`, pero la UI exige cambio. Revisar si la marca es de auth-ms o de Keycloak.
- No se cambió la clave: rompería `tools/e2e/07_rrhh_contratos_pagos.py`.

### Pendiente de decisión
- Probar apoderados y alumnos requiere claves temporales (DNI). Opciones: (a) cambiar la clave de cuentas de prueba a una clave de desarrollo registrada en docs; (b) no tocar claves y revisar solo con admin.

## Ronda 3: reintento de cuentas (tras actualizar la documentación)

- **H-1 ya no reproduce:** `lucia.mendoza` / `70234501` entra en Keycloak (`localhost:18090`) y en auth-ms (`localhost:8090`). Login en la interfaz OK.
- **H-4 nuevo: contradicción con la documentación.** `docs/datos-de-prueba-alumnos.md` (sección 6) dice que la clave DNI es "definitiva, no temporal" y que las cuentas "entran directamente". La interfaz muestra "Tu clave es temporal. Debes cambiarla para continuar" en `/cambiar-clave`. Revisar cuál es el comportamiento esperado.
- **Verificado solo en Keycloak** (no en auth-ms): `mateo.mendoza` / `71234511`, `carmen.rios` / `40123456`, `victor.puma` / `42100006` devuelven token. En auth-ms responden "Usuario o clave incorrectos". Si el frontend está en modo `keycloak-form`, Keycloak es el que valida; confirmar que ese es el modo de la demo.
- **Bloqueado por el paso anterior:** no se cambió ninguna clave. Ver H-3 (cambiar `docente123` rompe la suite e2e).

## Ronda 4: alumna y apoderada (login en la interfaz)

Entran sin pedir cambio de clave: `lucia.mendoza` / DNI y `carmen.rios` / DNI.

### Alumna (Lucía)
- Inicio, Horario, Notas, Asistencia, Tareas, Exámenes y Notificaciones cargan.
- **H-5: notas vacías con tarea calificada.** Matemática muestra "Ejercicios de fracciones" como *Calificada* y las notificaciones dicen "calificada: 17", pero Notas dice "Aún no hay notas registradas".
- **H-6: examen en dos listas.** "Examen de fracciones" aparece en *Disponibles para rendir* con "Sin intentos" (1 de 1 ya usado) y en *Resultados* con 15/20 "Pendiente de revisión". Revisar la etiqueta y la lista.
- **Pagos:** redirige al inicio con "No tienes acceso a esa sección". Puede ser correcto para alumna; confirmar.
- Horario: jueves 09:30 aparece vacío mientras el resto de la grilla tiene clases.

### Apoderada (Carmen, cuenta `carmen.rios`)
- **H-7: saludo con el nombre del hijo.** Dice "Buenas noches, Prueba Demo Prueba" y no el nombre de la apoderada.
- Cambio entre hijos (Prueba, Lucía, Mateo) funciona en Pagos, Asistencia y Horario.
- Prueba Demo: Horario "no está publicado" y Asistencia 0 %; cursos "Docente por asignar" (consistente con los datos).
- Pagos de Lucía: pagado S/ 400, por pagar S/ 3,002, vencido S/ 0. Coincide con la documentación.
- **H-8, no concluyente:** Mateo muestra exactamente los mismos montos y filas que Lucía. Verificar si es el dato real o si la vista no se refresca al cambiar de hijo.
- No se pagó nada (botón "Pagar lo que falta" sin usar).

### Docente
- Sigue bloqueado por H-3 (pide cambiar `docente123` en el primer ingreso). No se revisó su portal.

### Sin revisar en esta ronda
- Portal de docente, secretaría y caja; alumno sin apoderada; justificaciones (formulario sin enviar).

## Ronda 5: datos agregados por endpoints y verificación contra la API

### Datos agregados
- **20 docentes sin `cursoIds`** (aparecían como "sin cursos" en Cursos y docentes). Se actualizaron con `PUT /api/v1/planificacion/docentes/{id}`, con los cursos tomados de sus propias asignaciones (`GET /api/v1/planificacion/asignaciones?docenteId=`). Resultado: 20/20 con HTTP 200 y `cursoIds` iguales a los de sus asignaciones. Script: `tools/demo/...` no se guardó; el procedimiento está arriba.

### Hallazgos nuevos (la API tiene el dato, la interfaz no lo muestra)
- **H-9: "Sin tutor" en la interfaz.** `GET /api/v1/planificacion/secciones/18` (3 años A, 2027) devuelve `tutorDocenteId: 31` (Sosa Chura, Iván). El panel de aulas muestra "Sin tutor" y "0 docentes asignados", aunque `GET /asignaciones?seccionId=18` devuelve 5 asignaciones con docente. Lo mismo para las demás secciones 2027 con tutor.
- **H-10: "Sección sin docente" falso.** Las secciones 7 y 40 de la documentación figuran sin docente. La API tiene 9 asignaciones en la sección 40 (2027) y las 213 asignaciones de 2027 tienen `docenteId`. La documentación está desfasada o la interfaz usa otra fuente.
- **H-5 (causa):** las notas del alumno son BORRADOR: `GET /calificaciones/alumnos/4/libreta` devuelve Matemática con promedio 17.5 en BORRADOR. `GET /calificaciones/mias` (vista de alumna) devuelve `cursos: []`, por eso Notas dice "Aún no hay notas". Es regla de negocio (se publican al cerrar el periodo), no dato faltante. Pero la notificación "calificada: 17" aparece antes de que la nota sea visible.
- **H-11: nombre de curso en la libreta.** La libreta devuelve `cursoNombre: "Curso 1"` para el curso 1 (Matemática en el resto de la API). Corregir el mapeo.

### Sin agregar (requiere decisión)
- Cerrar periodo para publicar notas y generar boletines: afecta a todos los alumnos del periodo.
- Cuentas de acceso faltantes (219): implican contraseñas; no se crearon.
- Matrículas 2027: 191 PRE_MATRICULA, 6 MATRICULADO. Completar matrículas aprobadas implica cobros; no se hizo.

## Ronda 6: armado de horarios 2027 (en el navegador)

- Año 2027 seleccionado. Las 25 secciones quedaron marcadas (JS sobre las casillas; los botones "Todas de…" y "Solo…" reemplazan la selección en vez de sumarla).
- **Simular (no guarda):** las 25 secciones "Sin cambios", con horas completas (13/13, 24/24, 25/25). No se pulsó "Generar horarios" porque no cambiaría nada.
- "Horario por sección" (1° de Secundaria A, 2027) muestra la grilla completa, "Publicado: versión 2 · 19/11/2026 23:23".
- **H-12:** los botones "Todas de Inicial/Primaria/Secundaria" y "Solo X" reemplazan la selección. Con "Todas" de un nivel se pierden las marcas de los otros. Es confuso para el administrador.
- Clic en la ruta inventada `/planificacion/horario-seccion` redirige a la portada pública. La ruta real es `/planificacion/horarios`.

## Ronda 7: flujo de apoderada (pago) y revisión de matrículas

### Pago de la apoderada (Carmen, `carmen.rios`)
- Pagos → Demo Prueba → "Pagar lo que falta (S/ 200.00)". Pasarela de demostración (sin cargo real), tarjeta de prueba `4242…`, vencimiento `12/30`.
- Resultado: "Pago aprobado" TC-3495321671, S/ 200.00, VISA ****4242. Matrícula pasa a "Pagada" y "Estamos generando la matrícula".
- **H-13: el pago apunta a la matrícula de 2026.** La matrícula pagada es MAT-2026-000001 (año CERRADO). La familia paga una matrícula de un año cerrado. Revisar si la pantalla de pagos debería ofrecer solo el año activo.
- **H-14: el mensaje de tiempo.** En el formulario de pago aparece un contador "Vence 00:57" y el campo de vencimiento exige formato MM/AA; el error "El vencimiento va como MM/AA" aparece tras un intento con formato distinto. Verificar la validación.

### Revisión de matrículas (admin)
- En "Matrículas por aprobar" aparecen 4: MAT-2026-000001 (año cerrado) y MAT-2027-000018/19/20 (en línea, pagadas).
- Se abrió la revisión de MAT-2027-000020 (Renata Ibarra). **No se aprobó**: aprobar genera pensiones y envía correos con usuario y clave a los apoderados. Hace falta tu confirmación.
- Cuentas: la ficha muestra botones "Crear cuenta del alumno" y "Crear cuenta del apoderado" (sin cuenta todavía).

### Pendiente
- Flujo docente (crear tarea, poner nota) y alumno (entregar tarea): bloqueado por H-3 (`docente` pide cambio de clave en el primer ingreso).
- Aprobación de matrículas 2027: pendiente de confirmación.

## Ronda 8: revisión de notificaciones

- Bandeja admin: vacía (correcto, el admin no recibe avisos de otros).
- Plantillas: 21, activas (Asistencia, Boletín, Examen calificado/publicado, Horario publicado, Pago confirmado…). Revisar la plantilla de "Horario publicado" por ser la que más se envía.
- Avisos enviados: 35 en total. Revisadas las primeras filas: "Pago confirmado" (Usuario 111, correo y en la app) OK.

### H-15: avisos de horario con destinatario equivocado
- Los 13 avisos "Horario publicado" (3 años A, 4 años A, 5 años A, 4° de Primaria A, versiones 1 a 4) van todos a **Usuario 4** (docente Jorge Paredes).
- Paredes solo dicta en 4° de Primaria A. El tutor de 5 años A es Aguirre Mamani (docente 29, usuarioId 229). El aviso de 5 años A y de 3/4 años A no le corresponde.
- Revisar qué destinatario usa el evento de publicación de horario (probablemente un usuario fijo en vez del tutor de la sección o los docentes de la sección).

### H-16: contador de docentes en envío
- En "Enviar un aviso": "Docentes elegidos: 41. Se enviará a 31 usuario(s)" y "10 persona(s) no tienen una cuenta vinculada". Antes (ronda 1) decía 16 docentes. Cambió por nuevos docentes; el texto está bien, solo confirmar que el conteo es el esperado.
