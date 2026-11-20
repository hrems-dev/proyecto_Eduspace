package pe.edu.upeu.eduspace.pago.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.eduspace.pago.client.AlumnosGateway;
import pe.edu.upeu.eduspace.pago.client.MercadoPagoClient;
import pe.edu.upeu.eduspace.pago.client.PlanificacionClient;
import pe.edu.upeu.eduspace.pago.util.PlanPago;
import pe.edu.upeu.eduspace.pago.config.PagoProperties;
import pe.edu.upeu.eduspace.pago.dto.CreditoDtos.Movimiento;
import pe.edu.upeu.eduspace.pago.entity.Cuota;
import pe.edu.upeu.eduspace.pago.entity.MatriculaLocal;
import pe.edu.upeu.eduspace.pago.entity.OrdenPago;
import pe.edu.upeu.eduspace.pago.entity.SolicitudDescuento;
import pe.edu.upeu.eduspace.pago.exception.BusinessRuleException;
import pe.edu.upeu.eduspace.pago.exception.ResourceNotFoundException;
import pe.edu.upeu.eduspace.pago.repository.CuotaRepository;
import pe.edu.upeu.eduspace.pago.repository.MatriculaLocalRepository;
import pe.edu.upeu.eduspace.pago.repository.OrdenPagoRepository;
import pe.edu.upeu.eduspace.pago.repository.SolicitudDescuentoRepository;
import pe.edu.upeu.eduspace.pago.util.PasarelaSimulada;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * El pago de la matrícula, con todas las reglas en el servidor (los botones del frontend solo las reflejan):
 * cuánto falta (lo que debe la cuota de matrícula menos el crédito del alumno), la orden de pago, la confirmación del aviso de la
 * pasarela y «generar la matrícula» con el crédito. Pagar la matrícula (en caja, con la pasarela o con el crédito) es lo que hace que
 * matrícula-ms la genere.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatriculaPagoService {

    private static final List<String> POR_PAGAR = List.of("PENDIENTE", "PARCIAL", "VENCIDA");
    static final String MOTIVO_EN_REVISION = "Hay una solicitud de descuento en revisión: la secretaría debe darle su visto bueno antes de pagar o generar la matrícula.";

    private final CuotaRepository cuotas;
    private final MatriculaLocalRepository matriculas;
    private final OrdenPagoRepository ordenes;
    private final SolicitudDescuentoRepository solicitudes;
    private final CreditoService credito;
    private final AlumnosGateway alumnosGateway;
    private final PagoProperties props;
    private final PlanificacionClient planificacion;
    private final MercadoPagoClient mercadoPago;
    private final PagoService pagos;
    private final pe.edu.upeu.eduspace.pago.repository.PagoRepository pagoRepository;
    private final ComprobanteService comprobantes;

    // ------------------------------------------------------------------ lo que se muestra

    public record OrdenVista(String codigo, BigDecimal monto, String estado, LocalDateTime expiraEn) {}

    /** La última solicitud de descuento de la matrícula: en revisión, aprobada o rechazada (con su motivo). */
    public record SolicitudVista(Long id, String estado, String descuentoNombre, String tipo, BigDecimal valor, String motivo, String motivoResolucion) {}

    /** De qué es la matrícula (grado, sección, turno) y la forma de pago de las pensiones acordada, con su descuento. */
    public record DetalleMatricula(String grado, String seccion, String turno, String planPago, String planEtiqueta, BigDecimal planDescuento) {}

    public record EstadoMatricula(
        Long alumnoId, Long matriculaId, String estadoMatricula, Long cuotaId,
        BigDecimal importe, BigDecimal descuento, BigDecimal total, BigDecimal saldo, BigDecimal credito, BigDecimal faltante,
        boolean pagada, boolean puedeGenerar, String motivo, OrdenVista ordenVigente, List<Movimiento> depositos, SolicitudVista solicitudDescuento,
        DetalleMatricula detalle) {}

    /** Lo que ve Caja de un alumno que aún debe la matrícula. */
    public record PorPagar(Long alumnoId, String alumnoNombre, Long matriculaId, BigDecimal saldo, BigDecimal credito, BigDecimal faltante, boolean descuentoEnRevision,
                           DetalleMatricula detalle) {}

    /** Grado, sección, turno y forma de pago de una matrícula; si planificacion-ms no responde se muestran solo la forma de pago (no se rompe la pantalla). */
    DetalleMatricula detalleDe(MatriculaLocal m) {
        String grado = null, seccion = null, turno = null;
        try {
            var s = planificacion.getSeccion(m.getSeccionId());
            if (s != null) {
                seccion = s.nombre();
                turno = s.turno();
            }
            var g = planificacion.getGrado(m.getGradoId());
            if (g != null) grado = g.nombre();
        } catch (Exception e) {
            log.warn("Sin grado/sección de la matrícula {}: {}", m.getMatriculaId(), e.getMessage());
        }
        PlanPago plan = PlanPago.de(m.getPlanPago());
        BigDecimal descuentoPlan = switch (plan) {
            case SEMESTRAL -> props.getDescuentoPlanSemestral();
            case ANUAL -> props.getDescuentoPlanAnual();
            default -> BigDecimal.ZERO;
        };
        return new DetalleMatricula(grado, seccion, turno, plan.name(), plan.etiqueta(), descuentoPlan);
    }

    /** Lo que decide el servidor a partir de dos números: cuánto falta pagar y si el crédito ya alcanza para generar la matrícula. */
    public record Calculo(BigDecimal faltante, boolean creditoAlcanza) {}

    /** Falta pagar lo que debe la matrícula menos el crédito (nunca negativo); el crédito alcanza si cubre todo lo que debe. */
    public static Calculo calcular(BigDecimal saldoMatricula, BigDecimal creditoAlumno) {
        BigDecimal saldo = saldoMatricula == null ? BigDecimal.ZERO : saldoMatricula;
        BigDecimal cred = creditoAlumno == null ? BigDecimal.ZERO : creditoAlumno;
        return new Calculo(saldo.subtract(cred).max(BigDecimal.ZERO), saldo.signum() > 0 && cred.compareTo(saldo) >= 0);
    }

    @Transactional(readOnly = true)
    public EstadoMatricula estado(Long alumnoId, Long usuarioId, boolean personal) {
        exigirAcceso(alumnoId, usuarioId, personal);
        return estadoDe(alumnoId);
    }

    private EstadoMatricula estadoDe(Long alumnoId) {
        MatriculaLocal m = matriculaVigente(alumnoId);
        Cuota cuota = cuotas.findByMatriculaMatriculaId(m.getMatriculaId()).stream()
            .filter(c -> "MATRICULA".equals(c.getConcepto().getTipo())).findFirst().orElse(null);
        BigDecimal cred = credito.saldoDe(alumnoId);
        List<Movimiento> depositos = credito.ultimosDepositos(alumnoId, 5);
        OrdenPago orden = ordenes.findFirstByMatriculaIdAndEstado(m.getMatriculaId(), OrdenPago.PENDIENTE)
            .filter(o -> o.getExpiraEn().isAfter(LocalDateTime.now())).orElse(null);
        OrdenVista vista = orden == null ? null : new OrdenVista(orden.getCodigo(), orden.getMonto(), orden.getEstado(), orden.getExpiraEn());
        SolicitudVista sol = solicitudes.findFirstByMatriculaIdOrderByIdDesc(m.getMatriculaId())
            .map(x -> new SolicitudVista(x.getId(), x.getEstado(), x.getDescuento().getNombre(), x.getDescuento().getTipo(), x.getDescuento().getValor(),
                x.getMotivo(), x.getMotivoResolucion())).orElse(null);
        boolean enRevision = sol != null && SolicitudDescuento.PENDIENTE.equals(sol.estado());

        if (cuota == null) {
            return new EstadoMatricula(alumnoId, m.getMatriculaId(), m.getEstado(), null, null, null, null, BigDecimal.ZERO, cred, BigDecimal.ZERO,
                false, false, "Todavía no se generó la cuota de matrícula: falta configurar la tarifa (Apertura de periodo).", vista, depositos, sol, detalleDe(m));
        }
        boolean pagada = "PAGADA".equals(cuota.getEstado()) || cuota.getSaldo().signum() <= 0;
        BigDecimal saldo = pagada ? BigDecimal.ZERO : cuota.getSaldo();
        Calculo c = calcular(saldo, cred);
        String motivo = null;
        boolean puede = false;
        if (!"PRE_MATRICULA".equals(m.getEstado())) {
            motivo = "MATRICULADO".equals(m.getEstado()) ? "El alumno ya está matriculado." : "La matrícula está en estado " + m.getEstado() + ".";
        } else if (pagada) {
            motivo = "La matrícula ya está pagada: se genera en unos segundos.";
        } else if (enRevision) {
            motivo = MOTIVO_EN_REVISION;
        } else if (!c.creditoAlcanza()) {
            motivo = String.format("Faltan S/ %.2f: el crédito (S/ %.2f) no alcanza para la matrícula (S/ %.2f).", c.faltante(), cred, saldo);
        } else {
            puede = true;
        }
        return new EstadoMatricula(alumnoId, m.getMatriculaId(), m.getEstado(), cuota.getId(), cuota.getMontoBase(), cuota.getDescuento(),
            cuota.getMontoTotal(), saldo, cred, c.faltante(), pagada, puede, motivo, vista, depositos, sol, detalleDe(m));
    }

    /** La matrícula del alumno que se está pagando: la que sigue en pre-matrícula (la más reciente). */
    MatriculaLocal matriculaVigente(Long alumnoId) {
        List<MatriculaLocal> lista = matriculas.findByAlumnoId(alumnoId).stream()
            .sorted((a, b) -> b.getMatriculaId().compareTo(a.getMatriculaId())).toList();
        return lista.stream().filter(x -> "PRE_MATRICULA".equals(x.getEstado())).findFirst()
            .or(() -> lista.stream().findFirst())
            .orElseThrow(() -> new ResourceNotFoundException("El alumno no tiene una matrícula por pagar."));
    }

    // ------------------------------------------------------------------ orden de pago

    /** Crea (o reutiliza) la orden por lo que falta de la matrícula. El monto lo calcula el servidor. */
    @Transactional
    public OrdenVista crearOrden(Long alumnoId, Long usuarioId, boolean personal) {
        exigirAcceso(alumnoId, usuarioId, personal);
        EstadoMatricula e = estadoDe(alumnoId);
        if (!"PRE_MATRICULA".equals(e.estadoMatricula())) throw new BusinessRuleException(e.motivo());
        if (e.cuotaId() == null) throw new BusinessRuleException(e.motivo());
        if (e.pagada()) throw new BusinessRuleException("La matrícula ya está pagada.");
        if (e.solicitudDescuento() != null && SolicitudDescuento.PENDIENTE.equals(e.solicitudDescuento().estado())) throw new BusinessRuleException(MOTIVO_EN_REVISION);
        if (e.faltante().signum() <= 0) {
            throw new BusinessRuleException("El crédito del alumno ya cubre la matrícula: no hace falta pagar, genérala directamente.");
        }
        var vigente = ordenes.findFirstByMatriculaIdAndEstado(e.matriculaId(), OrdenPago.PENDIENTE);
        if (vigente.isPresent()) {
            OrdenPago o = vigente.get();
            if (o.getExpiraEn().isAfter(LocalDateTime.now()) && o.getMonto().compareTo(e.faltante()) == 0) {
                return new OrdenVista(o.getCodigo(), o.getMonto(), o.getEstado(), o.getExpiraEn());
            }
            // Cambió lo que falta (o venció): esa orden ya no sirve y se abre otra con el monto actual.
            o.setEstado(OrdenPago.EXPIRADA);
            ordenes.saveAndFlush(o);
        }
        OrdenPago nueva = ordenes.save(OrdenPago.builder()
            .codigo("ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase())
            .alumnoId(alumnoId).matriculaId(e.matriculaId()).monto(e.faltante()).creadaPor(usuarioId)
            .expiraEn(LocalDateTime.now().plusMinutes(props.getMinutosVigenciaOrden())).build());
        log.info("Orden {} por S/ {} para la matrícula {} del alumno {}.", nueva.getCodigo(), nueva.getMonto(), nueva.getMatriculaId(), alumnoId);
        return new OrdenVista(nueva.getCodigo(), nueva.getMonto(), nueva.getEstado(), nueva.getExpiraEn());
    }

    public record ResultadoPasarela(boolean aprobada, String transaccionId, String mensaje, String medio, String detalle, BigDecimal monto,
                                    LocalDateTime fecha, String orden, EstadoMatricula estado) {}

    /**
     * La pantalla de pago de la pasarela simulada: procesa los datos y, como haría una real, AVISA al servidor con el id de transacción y el
     * estado. Ese aviso pasa por el mismo {@link #confirmarOrden} que el webhook firmado.
     */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public ResultadoPasarela pagarSimulado(String codigo, PasarelaSimulada.Datos datos, Long usuarioId, boolean personal) {
        OrdenPago orden = ordenes.findByCodigo(codigo).orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + codigo));
        exigirAcceso(orden.getAlumnoId(), usuarioId, personal);
        // Con Mercado Pago activo, la pantalla de demostración no puede aprobar cobros: el dinero solo entra por la pasarela real.
        if (props.getMercadopago().habilitado()) throw new BusinessRuleException("La pasarela de demostración está desactivada: paga con Mercado Pago.");
        if (!OrdenPago.PENDIENTE.equals(orden.getEstado())) throw new BusinessRuleException("Esta orden ya está " + orden.getEstado().toLowerCase() + ".");
        if (orden.getExpiraEn().isBefore(LocalDateTime.now())) {
            orden.setEstado(OrdenPago.EXPIRADA);
            ordenes.save(orden);
            throw new BusinessRuleException("La orden venció: genera una nueva para pagar lo que falta.");
        }
        PasarelaSimulada.Resultado r = PasarelaSimulada.procesar(datos, YearMonth.now());
        Aviso aviso = new Aviso(codigo, r.transaccionId(), r.aprobada() ? "APROBADO" : "RECHAZADO", orden.getMonto(), r.medio(), r.pagador(), r.mensaje(), r.detalle());
        confirmarOrden(aviso);
        OrdenPago actual = ordenes.findByCodigo(codigo).orElseThrow();
        return new ResultadoPasarela(r.aprobada(), r.transaccionId(), r.mensaje(), r.medio(), r.detalle(), orden.getMonto(),
            actual.getConfirmadaEn() != null ? actual.getConfirmadaEn() : LocalDateTime.now(), codigo, estadoDe(orden.getAlumnoId()));
    }

    /** El aviso que manda la pasarela cuando termina un pago. */
    public record Aviso(String ordenCodigo, String transaccionId, String estado, BigDecimal monto, String medio, String pagador, String mensaje, String detalle) {}

    /**
     * Confirma una orden con el aviso de la pasarela. Es idempotente (el mismo aviso dos veces no cobra dos veces), exige que el monto sea
     * EXACTAMENTE el de la orden y, si el pago fue aprobado, suma el dinero al crédito del alumno y cobra la matrícula de ese crédito.
     */
    @Transactional
    public OrdenPago confirmarOrden(Aviso a) {
        OrdenPago o = ordenes.bloquearPorCodigo(a.ordenCodigo()).orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + a.ordenCodigo()));
        boolean aprobado = "APROBADO".equalsIgnoreCase(a.estado());

        if (o.getTransaccionId() != null && o.getTransaccionId().equals(a.transaccionId())) return o;           // aviso repetido
        if (!OrdenPago.PENDIENTE.equals(o.getEstado())) {
            if (aprobado) log.warn("ALERTA: aviso aprobado de la transacción {} para la orden {}, que ya está {}.", a.transaccionId(), o.getCodigo(), o.getEstado());
            throw new BusinessRuleException("La orden " + o.getCodigo() + " ya está " + o.getEstado().toLowerCase() + ".");
        }
        if (aprobado && (a.monto() == null || a.monto().compareTo(o.getMonto()) != 0)) {
            // El dinero SÍ entró y no se puede deshacer aquí: la orden no se confirma ni se cierra, queda para revisión manual con una alerta.
            // No se lanza excepción: un rollback borraría el registro de la alerta.
            o.setRespuesta("ALERTA: la transacción " + a.transaccionId() + " informó S/ " + a.monto() + " y la orden es por S/ " + o.getMonto() + ". Revisión manual.");
            ordenes.save(o);
            log.error("ALERTA: la transacción {} informó S/ {} pero la orden {} es por S/ {}. Revisión manual.", a.transaccionId(), a.monto(), o.getCodigo(), o.getMonto());
            return o;
        }

        o.setMedio(a.medio());
        o.setPagador(a.pagador());
        o.setRespuesta(a.mensaje() == null ? null : (a.mensaje().length() > 200 ? a.mensaje().substring(0, 200) : a.mensaje()));
        if (!aprobado) {
            // La pasarela rechazó este intento (datos mal, tarjeta de prueba de rechazo…): la orden sigue abierta para reintentar.
            ordenes.save(o);
            return o;
        }
        o.setConfirmadaEn(LocalDateTime.now());
        o.setTransaccionId(a.transaccionId());
        o.setEstado(OrdenPago.APROBADA);
        ordenes.saveAndFlush(o);

        String referencia = ("Op. " + a.transaccionId() + (a.detalle() == null ? "" : " · " + a.detalle()));
        credito.depositarSinCobro(o.getAlumnoId(), o.getMonto(), a.medio(), referencia.length() > 80 ? referencia.substring(0, 80) : referencia,
            "ORD-" + o.getCodigo(), null);
        // Con el dinero ya en el crédito, se cobra la matrícula (lo que la genera). Si por algo ya no se puede (p. ej. ya la pagaron en caja),
        // el dinero queda como crédito a favor del alumno y la orden queda aprobada.
        // Se comprueba ANTES sin lanzar excepciones (ver CreditoService.matriculaCobrable): un cobro fallido dentro de esta transacción la
        // marcaría para deshacer y se perdería el depósito del pago.
        if (credito.matriculaCobrable(o.getAlumnoId(), o.getMatriculaId())) {
            var r = credito.cobrarMatricula(o.getAlumnoId(), o.getMatriculaId(), null);
            o.setPagoId(r.pagoId());
            ordenes.save(o);
            emitirComprobanteDeOrden(r.pagoId(), a, referencia);
        } else {
            log.info("Orden {} aprobada; la matrícula de {} no se cobra del crédito ahora (queda a favor del alumno).", o.getCodigo(), o.getAlumnoId());
        }
        return o;
    }

    /**
     * El cobro de la matrícula sale del crédito, pero quien pagó fue la familia por la pasarela: el pago guarda quién y con qué operación,
     * y se emite su comprobante (el voucher que la familia descarga). Si falla la emisión no se deshace el cobro: el comprobante se emite
     * al descargarlo (ComprobanteService.obtenerOEmitir).
     */
    private void emitirComprobanteDeOrden(Long pagoId, Aviso a, String referencia) {
        try {
            pe.edu.upeu.eduspace.pago.entity.Pago pago = pagoRepository.findById(pagoId).orElseThrow();
            String pagador = a.pagador() != null && !a.pagador().isBlank() ? a.pagador() : "Apoderado";
            pago.setPagadorNombre(pagador.length() > 200 ? pagador.substring(0, 200) : pagador);
            String ref = (a.detalle() != null && a.detalle().startsWith("Mercado Pago") ? a.detalle() : "Pasarela") + " · Op. " + a.transaccionId();
            pago.setReferencia(ref.length() > 80 ? ref.substring(0, 80) : ref);
            pagoRepository.save(pago);
            comprobantes.emitirComprobante(pago, "RECIBO");
        } catch (RuntimeException e) {
            log.warn("No se emitió ahora el comprobante del pago {} ({}): se emitirá al descargarlo.", pagoId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Mercado Pago (pasarela real)

    public static final String MERCADOPAGO = "MERCADOPAGO";

    /** A dónde mandar a la familia para pagar, y si la cuenta es de prueba (PRUEBA) o la real (CUENTA). */
    public record PreferenciaVista(String codigo, String redireccion, String modo) {}

    /**
     * Crea (una sola vez por orden) la preferencia de Checkout Pro y devuelve la URL de pago. La tarjeta se ingresa en la pantalla de Mercado
     * Pago: este servidor nunca recibe datos de tarjeta. El monto es el de la orden, calculado por el servidor.
     */
    public PreferenciaVista pagarConMercadoPago(String codigo, Long usuarioId, boolean personal) {
        return pagarConMercadoPago(codigo, usuarioId, personal, "/familia/pagos");
    }

    /** {@code rutaVuelta}: a dónde vuelve la persona tras pagar (la familia o la matrícula en línea del sitio público). */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public PreferenciaVista pagarConMercadoPago(String codigo, Long usuarioId, boolean personal, String rutaVuelta) {
        OrdenPago orden = ordenes.findByCodigo(codigo).orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + codigo));
        exigirAcceso(orden.getAlumnoId(), usuarioId, personal);
        PagoProperties.MercadoPago mp = props.getMercadopago();
        if (!mp.habilitado()) throw new BusinessRuleException("El pago con Mercado Pago no está activo en este servidor.");
        if (!OrdenPago.PENDIENTE.equals(orden.getEstado())) throw new BusinessRuleException("Esta orden ya está " + orden.getEstado().toLowerCase() + ".");
        if (orden.getExpiraEn().isBefore(LocalDateTime.now())) {
            orden.setEstado(OrdenPago.EXPIRADA);
            ordenes.save(orden);
            throw new BusinessRuleException("La orden venció: genera una nueva para pagar lo que falta.");
        }
        if (orden.getPreferenciaUrl() == null) {
            String alumno = matriculas.findById(orden.getMatriculaId()).map(MatriculaLocal::getAlumnoNombre).orElse(null);
            String aviso = mp.getPublicBaseUrl() == null || mp.getPublicBaseUrl().isBlank() ? null
                : mp.getPublicBaseUrl().replaceAll("/+$", "") + "/api/v1/pagos/pasarela/mercadopago/notificacion";
            String ruta = rutaVuelta == null || rutaVuelta.isBlank() ? "/familia/pagos" : rutaVuelta;
            String vuelta = mp.getFrontendUrl().replaceAll("/+$", "") + ruta + (ruta.contains("?") ? "&" : "?") + "orden=" + orden.getCodigo();
            var pref = mercadoPago.crearPreferencia(orden.getCodigo(), orden.getMonto(),
                "Matrícula EduSpace" + (alumno == null ? "" : " - " + alumno), alumno, aviso, vuelta);
            orden.setPasarela(MERCADOPAGO);
            orden.setPreferenciaId(pref.id());
            orden.setPreferenciaUrl(pref.initPoint());
            ordenes.save(orden);
            log.info("Preferencia {} de Mercado Pago para la orden {} (S/ {}).", pref.id(), orden.getCodigo(), orden.getMonto());
        }
        return new PreferenciaVista(orden.getCodigo(), orden.getPreferenciaUrl(), mp.modo());
    }

    /**
     * Consulta a Mercado Pago los pagos de la orden y aplica los aprobados. Sirve cuando el aviso no llega (desarrollo sin URL pública) y al volver
     * de la pantalla de pago. Devuelve el estado de la matrícula ya actualizado.
     */
    @Transactional
    public EstadoMatricula sincronizarMercadoPago(String codigo, Long usuarioId, boolean personal) {
        OrdenPago orden = ordenes.findByCodigo(codigo).orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + codigo));
        exigirAcceso(orden.getAlumnoId(), usuarioId, personal);
        if (!MERCADOPAGO.equals(orden.getPasarela())) throw new BusinessRuleException("Esta orden no se cobra con Mercado Pago.");
        if (OrdenPago.PENDIENTE.equals(orden.getEstado())) {
            for (MercadoPagoClient.Pago p : mercadoPago.buscarPagos(orden.getCodigo())) procesarPagoMercadoPago(p);
        }
        return estadoDe(orden.getAlumnoId());
    }

    /** Resultado de un cobro hecho en nuestra pantalla: APROBADO, PENDIENTE (Mercado Pago lo revisa) o RECHAZADO, con el motivo en palabras. */
    /** {@code pagoId} y {@code comprobante}: el pago confirmado y su comprobante (voucher) cuando el cobro se aprobó. */
    public record ResultadoMercadoPago(String estado, String mensaje, String operacion, String referencia, Long pagoId, String comprobante) {
        public static ResultadoMercadoPago de(MercadoPagoClient.Pago p) {
            String e = switch (p.estado() == null ? "" : p.estado()) {
                case "approved" -> "APROBADO";
                case "in_process", "pending", "authorized" -> "PENDIENTE";
                default -> "RECHAZADO";
            };
            return new ResultadoMercadoPago(e, MercadoPagoClient.explicar(p.estado(), p.estadoDetalle()), String.valueOf(p.id()), p.referenciaExterna(),
                null, null);
        }

        public ResultadoMercadoPago conComprobante(Long pagoId, String comprobante) {
            return new ResultadoMercadoPago(estado, mensaje, operacion, referencia, pagoId, comprobante);
        }
    }

    /** El resultado con el pago y su comprobante, si el cobro quedó aprobado. */
    ResultadoMercadoPago conComprobante(ResultadoMercadoPago r, Long pagoId) {
        if (!"APROBADO".equals(r.estado()) || pagoId == null) return r;
        String numero = comprobantes.buscarPorPago(pagoId)
            .map(c -> String.format("%s-%08d", c.getSerie(), c.getNumero())).orElse(null);
        return r.conComprobante(pagoId, numero);
    }

    /**
     * Cobra la orden en nuestra propia pantalla (tarjeta o Yape) con el token de MercadoPago.js. El monto es el de la orden; el resultado se
     * aplica en el acto con la misma regla que el aviso (monto exacto, idempotente por id de pago). Un rechazo no cierra la orden.
     */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public ResultadoMercadoPago pagarDirecto(String codigo, MercadoPagoClient.DatosCobro datos, Long usuarioId, boolean personal) {
        OrdenPago orden = ordenes.findByCodigo(codigo).orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + codigo));
        exigirAcceso(orden.getAlumnoId(), usuarioId, personal);
        PagoProperties.MercadoPago mp = props.getMercadopago();
        if (!mp.habilitado()) throw new BusinessRuleException("El pago con Mercado Pago no está activo en este servidor.");
        if (datos == null || datos.token() == null || datos.token().isBlank() || datos.metodo() == null) {
            throw new BusinessRuleException("Faltan los datos del pago.");
        }
        if (datos.email() == null || !datos.email().contains("@")) throw new BusinessRuleException("Escribe un correo válido para el comprobante de Mercado Pago.");
        if (!OrdenPago.PENDIENTE.equals(orden.getEstado())) throw new BusinessRuleException("Esta orden ya está " + orden.getEstado().toLowerCase() + ".");
        if (orden.getExpiraEn().isBefore(LocalDateTime.now())) {
            orden.setEstado(OrdenPago.EXPIRADA);
            ordenes.save(orden);
            throw new BusinessRuleException("La orden venció: genera una nueva para pagar lo que falta.");
        }
        orden.setPasarela(MERCADOPAGO);
        ordenes.save(orden);
        String alumno = matriculas.findById(orden.getMatriculaId()).map(MatriculaLocal::getAlumnoNombre).orElse(null);
        String aviso = mp.getPublicBaseUrl() == null || mp.getPublicBaseUrl().isBlank() ? null
            : mp.getPublicBaseUrl().replaceAll("/+$", "") + "/api/v1/pagos/pasarela/mercadopago/notificacion";
        MercadoPagoClient.Pago p = mercadoPago.cobrar(orden.getCodigo(), orden.getMonto(),
            "Matrícula EduSpace" + (alumno == null ? "" : " - " + alumno), datos, aviso);
        log.info("Cobro {} de Mercado Pago ({}) para la orden {}: {} {}", p.id(), datos.metodo(), orden.getCodigo(), p.estado(), p.estadoDetalle());
        procesarPagoMercadoPago(p);
        Long pagoId = ordenes.findByCodigo(orden.getCodigo()).map(OrdenPago::getPagoId).orElse(null);
        return conComprobante(ResultadoMercadoPago.de(p), pagoId);
    }

    /** Matrícula en línea: el PDF del comprobante del último pago aprobado con Mercado Pago del alumno. */
    @Transactional
    public byte[] comprobanteMatriculaPublica(Long alumnoId) {
        OrdenPago o = ordenes.findFirstByAlumnoIdAndPasarelaOrderByIdDesc(alumnoId, MERCADOPAGO)
            .filter(x -> OrdenPago.APROBADA.equals(x.getEstado()) && x.getPagoId() != null)
            .orElseThrow(() -> new ResourceNotFoundException("Todavía no hay un pago aprobado de esta matrícula."));
        var pago = pagoRepository.findById(o.getPagoId()).orElseThrow();
        return comprobantes.pdf(comprobantes.obtenerOEmitir(pago));
    }

    /** Matrícula en línea del sitio público: cobra la orden de lo que falta del alumno (la crea o reutiliza). */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public ResultadoMercadoPago pagarDirectoMatriculaPublica(Long alumnoId, MercadoPagoClient.DatosCobro datos) {
        String codigo = crearOrden(alumnoId, null, true).codigo();
        return pagarDirecto(codigo, datos, null, true);
    }

    /** El aviso (webhook) de Mercado Pago: solo trae el id del pago; el estado y el monto se piden al API antes de confiar en ellos. */
    @Transactional
    public void procesarAvisoMercadoPago(long paymentId) {
        procesarPagoMercadoPago(mercadoPago.obtenerPago(paymentId));
    }

    /**
     * Traduce un pago de Mercado Pago a un aviso de la pasarela y lo confirma con la misma regla que las demás pasarelas (monto exacto, idempotente).
     * Los pagos pendientes (en proceso, sin aprobar) no hacen nada hasta que Mercado Pago informe el resultado.
     */
    void procesarPagoMercadoPago(MercadoPagoClient.Pago p) {
        if (p.referenciaExterna() == null) return;
        if (p.referenciaExterna().startsWith(PagoOnlineService.PREFIJO_REFERENCIA)) {
            procesarPensionMercadoPago(p);   // pensiones del apoderado: el pago pendiente, confirmado por la pasarela
            return;
        }
        String estado = switch (p.estado() == null ? "" : p.estado()) {
            case "approved" -> "APROBADO";
            case "rejected", "cancelled" -> "RECHAZADO";
            default -> null;
        };
        if (estado == null) {
            log.info("Pago {} de Mercado Pago en estado {}: se espera su resultado.", p.id(), p.estado());
            return;
        }
        String metodo = p.metodo() == null ? "Mercado Pago" : "Mercado Pago · " + p.metodo();
        Aviso aviso = new Aviso(p.referenciaExterna(), String.valueOf(p.id()), estado, p.monto(), medioDe(p.tipo()), p.pagador(),
            p.estadoDetalle(), metodo);
        try {
            confirmarOrden(aviso);
        } catch (BusinessRuleException | ResourceNotFoundException e) {
            log.info("Pago {} de Mercado Pago no aplicado a la orden {}: {}", p.id(), p.referenciaExterna(), e.getMessage());
        }
    }

    private void procesarPensionMercadoPago(MercadoPagoClient.Pago p) {
        long pagoId;
        try {
            pagoId = Long.parseLong(p.referenciaExterna().substring(PagoOnlineService.PREFIJO_REFERENCIA.length()));
        } catch (NumberFormatException e) {
            log.warn("Referencia de Mercado Pago no reconocida: {}", p.referenciaExterna());
            return;
        }
        switch (p.estado() == null ? "" : p.estado()) {
            case "approved" -> pagos.confirmarPorPasarela(pagoId, p.monto());
            case "rejected", "cancelled" -> pagos.rechazarPorPasarela(pagoId, "Mercado Pago no aprobó el pago" + (p.estadoDetalle() == null ? "." : " (" + p.estadoDetalle() + ")."));
            default -> log.info("Pago de pensiones {} en estado {} en Mercado Pago: se espera su resultado.", pagoId, p.estado());
        }
    }

    /**
     * Consulta UNA vez a Mercado Pago los pagos de una referencia y aplica los aprobados. Es el respaldo cuando el aviso no llega; se llama solo
     * cuando la persona vuelve de la pantalla de pago (nunca sondeando), para no gastar el cupo de la API.
     */
    @Transactional
    public void sincronizarReferencia(String referencia) {
        for (MercadoPagoClient.Pago p : mercadoPago.buscarPagos(referencia)) procesarPagoMercadoPago(p);
    }

    /** Respaldo de la matrícula en línea del sitio público: la última orden de Mercado Pago del alumno, si todavía está pendiente. */
    @Transactional
    public void sincronizarMatriculaPublica(Long alumnoId) {
        if (!props.getMercadopago().habilitado()) return;
        ordenes.findFirstByAlumnoIdAndPasarelaOrderByIdDesc(alumnoId, MERCADOPAGO)
            .filter(o -> OrdenPago.PENDIENTE.equals(o.getEstado()))
            .ifPresent(o -> sincronizarReferencia(o.getCodigo()));
    }

    /** Solo los medios que el crédito acepta: las tarjetas como TARJETA y lo demás (transferencias, billeteras, efectivo en agente) como TRANSFERENCIA. */
    static String medioDe(String tipoPago) {
        if (tipoPago == null) return "TRANSFERENCIA";
        return switch (tipoPago) {
            case "credit_card", "debit_card", "prepaid_card" -> "TARJETA";
            default -> "TRANSFERENCIA";
        };
    }

    // ------------------------------------------------------------------ generar con el crédito

    /** «Generar matrícula»: con el crédito suficiente cobra la matrícula del crédito. El servidor valida todo (el botón solo lo refleja). */
    @Transactional
    public EstadoMatricula generar(Long alumnoId, Long usuarioId, boolean personal) {
        exigirAcceso(alumnoId, usuarioId, personal);
        EstadoMatricula e = estadoDe(alumnoId);
        if (!e.puedeGenerar()) throw new BusinessRuleException(e.motivo() != null ? e.motivo() : "No se puede generar la matrícula.");
        credito.cobrarMatricula(alumnoId, e.matriculaId(), usuarioId);
        return estadoDe(alumnoId);
    }

    // ------------------------------------------------------------------ caja

    /** Los alumnos con pre-matrícula que aún deben la matrícula (lo que Caja necesita cobrar). */
    @Transactional(readOnly = true)
    public List<PorPagar> porPagar() {
        return matriculas.findByEstadoOrderByMatriculaIdDesc("PRE_MATRICULA").stream()
            .map(m -> cuotas.findByMatriculaMatriculaId(m.getMatriculaId()).stream()
                .filter(c -> "MATRICULA".equals(c.getConcepto().getTipo()) && POR_PAGAR.contains(c.getEstado()) && c.getSaldo().signum() > 0)
                .findFirst()
                .map(c -> {
                    BigDecimal cred = credito.saldoDe(m.getAlumnoId());
                    return new PorPagar(m.getAlumnoId(), m.getAlumnoNombre(), m.getMatriculaId(), c.getSaldo(), cred, calcular(c.getSaldo(), cred).faltante(),
                        solicitudes.existsByMatriculaIdAndEstado(m.getMatriculaId(), SolicitudDescuento.PENDIENTE), detalleDe(m));
                }).orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    // ------------------------------------------------------------------ acceso

    /** El personal (caja, secretaría, administrador) puede con cualquier alumno; el apoderado, solo con sus hijos. */
    void exigirAcceso(Long alumnoId, Long usuarioId, boolean personal) {
        if (personal) return;
        boolean esHijo = usuarioId != null && alumnosGateway.hijos(usuarioId).stream().anyMatch(h -> alumnoId.equals(h.getAlumnoId()));
        if (!esHijo) throw new AccessDeniedException("Solo puede pagar la matrícula de sus propios hijos.");
    }
}
