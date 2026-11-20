# Datos de prueba: alumnos, apoderados, docentes y cuentas

Estado de la base de datos de desarrollo al 19/11/2026 (23:10). Todos los nombres son ficticios.
Fuentes: `e2e-pg-alumnos`, `e2e-pg-matricula`, `e2e-pg-pago`, `e2e-pg-planificacion` y `e2e-pg-auth`.

> **Sobre las cuentas:** en el modo Keycloak, el `usuario_id` de alumnos, apoderados y docentes es el atributo
> `usuarioId` del token, no el `id` de la tabla `usuarios` de auth-ms. Por eso el `usuario_id` de abajo puede
> no coincidir con el usuario que se ve en `e2e-pg-auth`. Antes de dar un acceso, verifica el usuario en Keycloak.

---

## 1. Alumnos y sus matrículas

Los montos son el **saldo** de las cuotas (por pagar = pendientes y parciales; vencido = cuotas vencidas).

| ID | Documento | Alumno | Estado | Cuenta (usuario_id) | Matrícula 2026 | Matrícula 2027 | Deuda 2026 | Deuda 2027 |
|---:|---|---|---|---|---|---|---:|---:|
| 1 | 71234567 | Prueba Demo Prueba | ACTIVO | sin cuenta | PRE_MATRICULA (MAT-2026-000001) | — | 200.00 | — |
| 2 | 71458296 | Lucía Andrea Ramírez Quispe | ACTIVO | **10** (`lucia.ramirez2`) | MATRICULADO (MAT-2026-000002), sección 17 | — | 150.00 + **1 350.00 vencido** | — |
| 3 | 12345786 | norma lan llante mentes | ACTIVO | sin cuenta | PRE_MATRICULA (MAT-2026-000003) | — | 200.00 | — |
| 4 | 70234501 | Lucía Mendoza Ríos | ACTIVO | **6** (`lucia.mendoza`) | — | MATRICULADO (MAT-2027-000004), 4° Primaria A | — | 3 002.00 |
| 5 | 71234511 | Mateo Mendoza Ríos | ACTIVO | sin cuenta | — | MATRICULADO (MAT-2027-000005), 4° Primaria A | — | 3 002.00 |
| 6 | 72234521 | Sofía Vargas Luna | ACTIVO | sin cuenta | — | MATRICULADO (MAT-2027-000006), 4° Primaria A | — | 3 002.00 |
| 7 | 73234531 | Diego Salas Vega | ACTIVO | sin cuenta | — | PRE_MATRICULA (MAT-2027-000007), 4° Primaria A | — | 400.00 |
| 8 | 60000001 | Camila Quispe Rojas | ACTIVO | sin cuenta | — | — | — | — |
| 9 | 60000002 | Joaquín Quispe Rojas | ACTIVO | sin cuenta | — | — | — | — |
| 10 | 60000003 | Valeria Ramírez Castro | ACTIVO | sin cuenta | — | — | — | — |
| 11 | 60000004 | Santiago Huamán Poma | ACTIVO | sin cuenta | — | — | — | — |
| 12 | 60000005 | Renata Huamán Poma | ACTIVO | sin cuenta | — | — | — | — |
| 13 | 60000006 | Andrea Torres Díaz | ACTIVO | sin cuenta | — | — | — | — |
| 14 | 60000007 | Bruno Flores Soto | ACTIVO | sin cuenta | — | — | — | — |
| 15 | 60000008 | Daniela Flores Soto | ACTIVO | sin cuenta | — | — | — | — |
| 16 | 60000009 | Emilia Medina Cruz | ACTIVO | sin cuenta | — | — | — | — |
| 17 | 60000010 | Nicolás Salazar Paz | ACTIVO | sin cuenta | — | — | — | — |
| 18 | 60000011 | Mía Vera León | ACTIVO | sin cuenta | — | — | — | — |
| 19 | 60000012 | Thiago Vera León | ACTIVO | sin cuenta | — | — | — | — |
| 20 | 60000013 | Mariana Aguilar Ortiz | ACTIVO | sin cuenta | — | — | — | — |
| 21 | 60000014 | Sebastián Campos Reyes | ACTIVO | sin cuenta | — | — | — | — |
| 22 | 60000015 | Isabella Navarro Cano | ACTIVO | sin cuenta | — | — | — | — |
| 23 | 60000016 | Matías Navarro Cano | ACTIVO | sin cuenta | — | — | — | — |
| 24 | 60000017 | Antonella Alvarado Peña | ACTIVO | sin cuenta | — | — | — | — |
| 25 | 74100001 | Alessia Pillco Ayma | ACTIVO | sin cuenta | PRE_MATRICULA (MAT-2026-000025), 4° Primaria A | PRE_MATRICULA (MAT-2027-000008), 4° Primaria A | 300.00 | 200.00 (parcial) |
| 26 | 74100002 | Bryan Cáceres Villanueva | ACTIVO | sin cuenta | — | ANULADO (MAT-2027-000009) | — | — |
| 27 | 74100003 | Milagros Ortiz Chura | ACTIVO | sin cuenta | MATRICULADO (MAT-2026-000023), 4° Primaria A | MATRICULADO (MAT-2027-000010), 4° Primaria A | 210.00 + 1 890.00 vencido | 3 002.00 |
| 28 | 74100004 | Gael Ramos Pari | ACTIVO | sin cuenta | MATRICULADO (MAT-2026-000021), 4° Primaria A | MATRICULADO (MAT-2027-000011), 4° Primaria A | 210.00 + 1 890.00 vencido | 3 002.00 |
| 29 | 74100005 | Fernanda Soto Mamani | ACTIVO | sin cuenta | MATRICULADO (MAT-2026-000024), 4° Primaria A | RETIRADO (MAT-2027-000012); además hay RETIRADO duplicados MAT-2027-000015 y -000017 | 210.00 + 1 890.00 vencido | 0 |
| 30 | 74100006 | Joel Vásquez Puma | ACTIVO | sin cuenta | MATRICULADO (MAT-2026-000022), 4° Primaria A | MATRICULADO (MAT-2027-000013), 4° Primaria A | 210.00 + 1 890.00 vencido | 3 002.00 |
| 31 | 76046775 | Valeria Camacho Soto | ACTIVO | sin cuenta | — | POR_APROBAR (MAT-2027-000018), 1° Secundaria B | — | 0 (cuota de matrícula pagada) |
| 32 | 76046776 | Mateo Salinas Ruiz | ACTIVO | sin cuenta | — | POR_APROBAR (MAT-2027-000019), 1° Secundaria B | — | 0 (cuota de matrícula pagada) |
| 33 | 76046777 | Renata Ibarra León | ACTIVO | sin cuenta | — | POR_APROBAR (MAT-2027-000020), 1° Secundaria B | — | 0 (cuota de matrícula pagada) |

Notas:

- **Alumnos duplicados:** no hay alumnos repetidos (ningún nombre ni documento se repite en la tabla `alumnos`).
  Lo que sí estaba repetido eran **matrículas** de Bryan (3 anuladas) y Fernanda (3 retiradas en 2027).
- **Bryan:** se eliminaron las matrículas duplicadas MAT-2027-000014 y MAT-2027-000016, con sus cuotas anuladas.
  Queda la MAT-2027-000009.
- **Fernanda: pendiente.** Las matrículas MAT-2027-000015 y MAT-2027-000017 tienen cuotas **PAGADAS** (dos pagos de matrícula).
  No las borré porque eliminarlas quitaría pagos del historial de cobros. Hay que decidir si se anulan o se dejan.
- Los alumnos 25 a 30 tienen sus matrículas de 2026 cargadas en el año cerrado.

---

## 2. Apoderados

| ID | Documento | Apoderado | Teléfono | Correo | Cuenta (usuario_id) | Alumnos a cargo |
|---:|---|---|---|---|---|---|
| 1 | 40123456 | Madre Demo Prueba | 999111220 | carmen@ejemplo.com | 5 (ver nota) | 1 Prueba Demo, 4 Lucía Mendoza, 5 Mateo Mendoza |
| 2 | 48213057 | Carmen Quispe Huamán | 913456782 | carmen.quispe@ejemplo.pe | **11** (`carmen.quispe2`) | 2 Lucía Andrea Ramírez |
| 3 | 12321456 | carlos zarsa | 8237373 | carlos.s@gmail.com | sin cuenta | 3 norma lan |
| 4 | 40777888 | Jorge Vargas | 999111222 | jvargas@ejemplo.com | sin cuenta | 6 Sofía Vargas |
| 5 | 40555666 | Rosa Vega | 999111223 | rvega@ejemplo.com | sin cuenta | 7 Diego Salas |
| 6 | 41200101 | Marta Quispe | 988001101 | mquispe@ejemplo.com | sin cuenta | 8 Camila, 9 Joaquín Quispe |
| 7 | 41200102 | Luis Ramírez | 988001103 | lramirez@ejemplo.com | sin cuenta | 10 Valeria Ramírez |
| 8 | 41200103 | Elena Huamán | 988001104 | ehuaman@ejemplo.com | sin cuenta | 11 Santiago, 12 Renata Huamán |
| 9 | 41200104 | Carlos Torres | 988001106 | ctorres@ejemplo.com | sin cuenta | 13 Andrea Torres |
| 10 | 41200105 | Patricia Flores | 988001107 | pflores@ejemplo.com | sin cuenta | 14 Bruno, 15 Daniela Flores |
| 11 | 41200106 | Raúl Medina | 988001109 | rmedina@ejemplo.com | sin cuenta | 16 Emilia Medina |
| 12 | 41200107 | Gabriela Salazar | 988001110 | gsalazar@ejemplo.com | sin cuenta | 17 Nicolás Salazar |
| 13 | 41200108 | Fernando Vera | 988001111 | fvera@ejemplo.com | sin cuenta | 18 Mía, 19 Thiago Vera |
| 14 | 41200109 | Rosa Aguilar | 988001113 | raguilar@ejemplo.com | sin cuenta | 20 Mariana Aguilar |
| 15 | 41200110 | Jorge Campos | 988001114 | jcampos@ejemplo.com | sin cuenta | 21 Sebastián Campos |
| 16 | 41200111 | Lucía Navarro | 988001115 | lnavarro@ejemplo.com | sin cuenta | 22 Isabella, 23 Matías Navarro |
| 17 | 41200112 | Hugo Alvarado | 988001117 | halvarado@ejemplo.com | sin cuenta | 24 Antonella Alvarado |
| 18 | 42100001 | Rosa Ayma | 99911101 | apoderado1@ejemplo.com | sin cuenta | 25 Alessia Pillco |
| 19 | 42100002 | Hugo Villanueva | 99911102 | apoderado2@ejemplo.com | sin cuenta | 26 Bryan Cáceres |
| 20 | 42100003 | Elena Chura | 99911103 | apoderado3@ejemplo.com | sin cuenta | 27 Milagros Ortiz |
| 21 | 42100004 | Marco Pari | 99911104 | apoderado4@ejemplo.com | sin cuenta | 28 Gael Ramos |
| 22 | 42100005 | Nelly Mamani | 99911105 | apoderado5@ejemplo.com | sin cuenta | 29 Fernanda Soto |
| 23 | 42100006 | Víctor Puma | 99911106 | apoderado6@ejemplo.com | sin cuenta | 30 Joel Vásquez |
| 24 | 46046775 | Rosa Camacho Reyes | 999111222 | familia.46046775@ejemplo.pe | sin cuenta | 31 Valeria Camacho |
| 25 | 46046776 | Luis Salinas Mora | 999111222 | familia.46046776@ejemplo.pe | sin cuenta | 32 Mateo Salinas |
| 26 | 46046777 | Elena Ibarra Gómez | 999111222 | familia.46046777@ejemplo.pe | sin cuenta | 33 Renata Ibarra |

Nota: el apoderado 1 (Madre Demo Prueba, correo carmen@ejemplo.com) figura vinculado al usuario 5, que en `e2e-pg-auth`
es `miguel.paredes`. En la semilla, el usuario 5 debía ser la apoderada Carmen Ríos. Hay que revisar ese vínculo.

---

## 3. Docentes y secciones

| Docente | Documento | Especialidad | Cuenta (usuario_id) | Asignaciones 2027 | Tutoría |
|---|---|---|---|---|---|
| Jorge Paredes | 40111222 | Matemática | **4** (`docente`, Keycloak) | 4° Primaria A: Matemática, Comunicación y Ciencia y Tecnología | 4° Primaria A |
| Rosa Aguirre Lazo | 70000001 | Educación Primaria | sin cuenta | sin asignar | — |
| Luis Benites Cruz | 70000002 | Educación Primaria | sin cuenta | sin asignar | — |
| Marta Cáceres Vidal | 70000003 | Educación Primaria | sin cuenta | sin asignar | — |
| Pedro Delgado Rivas | 70000004 | Educación Primaria | sin cuenta | sin asignar | — |
| Ana Espinoza Paz | 70000005 | Educación Primaria | sin cuenta | sin asignar | — |
| Julio Farfán Ochoa | 70000006 | Educación Primaria | sin cuenta | sin asignar | — |
| Carmen Gamarra Soto | 70000007 | Educación Primaria | sin cuenta | sin asignar | — |
| Hugo Herrera Lima | 70000008 | Educación Primaria | sin cuenta | sin asignar | — |
| Elena Ibáñez Cano | 70000009 | Educación Primaria | sin cuenta | sin asignar | — |
| Raúl Jiménez Poma | 70000010 | Educación Primaria | sin cuenta | sin asignar | — |
| Sofía Landa Reyes | 70000011 | Educación Primaria | sin cuenta | sin asignar | — |
| Diego Medina Arce | 70000012 | Educación Primaria | sin cuenta | sin asignar | — |
| Valeria Ñahui Quispe | 70000013 | Arte y Cultura | sin cuenta | sin asignar | — |
| Andrés Olivera Ramos | 70000014 | Educación Física | sin cuenta | sin asignar | — |
| Teresa Peralta Gil | 70000015 | Inglés | sin cuenta | sin asignar | — |

Secciones sin docente: 4° Primaria A de 2026 (sección 7; alumnos 25, 27, 28, 29 y 30) y 1° Secundaria B de 2027
(sección 40; alumnos 31, 32 y 33). Las matrículas de 2026 no tienen docente asignado en esa sección.

---

## 4. Cuentas de usuario (auth-ms, tabla `usuarios`)

| id | Usuario | Nombre | Vinculado a | Observación |
|---:|---|---|---|---|
| 1 | admin | Administrador EduSpace | — | Administrador |
| 2 | secretaria | Secretaría EduSpace | — | Secretaría |
| 3 | cajero | Caja EduSpace | — | Caja |
| 4 | carmen.huaman | Carmen Rosa Huamán Ccori | — | No corresponde al docente Jorge Paredes (usuario 4 en Keycloak) |
| 5 | miguel.paredes | Miguel Ángel Paredes Rojas | apoderado 1 | Revisar vínculo |
| 6 | lucia.mendoza | Lucía Mendoza Quispe | alumna 4 | Correcto |
| 7 | jorge.vargas | Jorge Vargas | — | Sin vínculo |
| 8 | lucia.ramirez | Lucía Andrea Ramírez Quispe | — | **Duplicado** de `lucia.ramirez2` (10); sin vínculo |
| 9 | carmen.quispe | Carmen Quispe Huamán | — | **Duplicado** de `carmen.quispe2` (11); sin vínculo |
| 10 | lucia.ramirez2 | Lucía Andrea Ramírez Quispe | alumna 2 | Correcto |
| 11 | carmen.quispe2 | Carmen Quispe Huamán | apoderado 2 | Correcto |

---

## 5. Pendientes

1. **Fernanda (alumna 29):** decidir qué hacer con MAT-2027-000015 y MAT-2027-000017, que tienen pagos de matrícula registrados.
2. **Cuentas duplicadas en auth-ms:** `lucia.ramirez` (8) y `carmen.quispe` (9) no tienen vínculo y repiten a una persona con otra cuenta. Confirmar antes de borrarlas.
3. **Vínculo del apoderado 1** con el usuario 5 (`miguel.paredes`).
4. **Cuentas faltantes:** 31 alumnos, 24 apoderados y 15 docentes no tienen cuenta. Para crearlas hace falta definir la clave inicial (en la memoria del proyecto se usa el número de documento y el sistema pide cambiarla al entrar).
5. **Docentes sin asignar:** 15 docentes sin cursos ni sección; sección 7 y sección 40 sin docente.

---

## 6. Actualización: cuentas de la cadena 4° de Primaria A (2027)

Creadas con `tools/demo/cuentas_relacionadas.py` en Keycloak (realm `eduspace`) y vinculadas en EduSpace.
La clave de cada cuenta es el número de documento (clave definitiva de desarrollo, no temporal): entran directamente.
Verificado: las 15 cuentas (incluida `lucia.mendoza`) inician sesión con su clave.

| Rol | Usuario | Ficha | Documento (clave inicial) | usuarioId |
|---|---|---|---|---:|
| Docente (sección 4° A, tutor) | `docente` (ya existía) | Jorge Paredes, docente 1 | 40111222 | 4 |
| Alumna | `lucia.mendoza` (ya existía) | Lucía Mendoza, alumna 4 | 70234501 | 6 |
| Alumno | `mateo.mendoza` | Mateo Mendoza, alumno 5 | 71234511 | 101 |
| Alumna | `sofia.vargas` | Sofía Vargas, alumna 6 | 72234521 | 102 |
| Alumno | `diego.salas` | Diego Salas, alumno 7 (pre-matrícula) | 73234531 | 103 |
| Alumna | `alessia.pillco` | Alessia Pillco, alumna 25 (pago parcial) | 74100001 | 104 |
| Alumna | `milagros.ortiz` | Milagros Ortiz, alumna 27 | 74100003 | 105 |
| Alumno | `gael.ramos` | Gael Ramos, alumno 28 | 74100004 | 106 |
| Alumno | `joel.vasquez` | Joel Vásquez, alumno 30 | 74100006 | 107 |
| Apoderada (de Lucía y Mateo) | `carmen.rios` | Madre Demo Prueba, apoderado 1 | 40123456 | 111 |
| Apoderado (de Sofía) | `jorge.vargas.apo` | Jorge Vargas, apoderado 4 | 40777888 | 112 |
| Apoderada (de Diego) | `rosa.vega` | Rosa Vega, apoderado 5 | 40555666 | 113 |
| Apoderada (de Alessia) | `rosa.ayma` | Rosa Ayma, apoderado 18 | 42100001 | 114 |
| Apoderada (de Milagros) | `elena.chura` | Elena Chura, apoderado 20 | 42100003 | 115 |
| Apoderado (de Gael) | `marco.pari` | Marco Pari, apoderado 21 | 42100004 | 116 |
| Apoderado (de Joel) | `victor.puma` | Víctor Puma, apoderado 23 | 42100006 | 117 |

Verificado: los 15 vínculos están en la base. Mateo y Lucía apuntan al mismo apoderado 1. El vínculo erróneo del
apoderado 1 con el usuario 5 de auth-ms ya fue reemplazado por `carmen.rios` (111).

Antes, el inicio de sesión respondía «Account is not fully set up» porque la clave era temporal. Ya está corregido.

Pendiente: el horario y el resto de los apoderados y alumnos de otras secciones siguen sin cuenta.
