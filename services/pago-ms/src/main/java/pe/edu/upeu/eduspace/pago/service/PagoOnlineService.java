package pe.edu.upeu.eduspace.pago.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.eduspace.pago.client.AlumnosGateway;
import pe.edu.upeu.eduspace.pago.client.MercadoPagoClient;
import pe.edu.upeu.eduspace.pago.config.PagoProperties;
import pe.edu.upeu.eduspace.pago.dto.PagoResponse;
import pe.edu.upeu.eduspace.pago.dto.RegistrarPagoRequest;
import pe.edu.upeu.eduspace.pago.entity.Pago;
import pe.edu.upeu.eduspace.pago.exception.BusinessRuleException;
import pe.edu.upeu.eduspace.pago.repository.CuotaRepository;
import pe.edu.upeu.eduspace.pago.repository.PagoRepository;

/**
 * Pago de las pensiones en línea del apoderado. Con Mercado Pago activo: se registra un pago pendiente (las cuotas quedan reservadas) y el
 * apoderado va a la pantalla de Mercado Pago; el aviso verificado confirma o rechaza el pago. Sin credenciales queda la pasarela de demostración.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PagoOnlineService {

    public static final String PREFIJO_REFERENCIA = "PG-";

    private final PagoService pagoService;
    private final AlumnosGateway alumnosGateway;
    private final CuotaRepository cuotaRepository;
    private final PagoRepository pagoRepository;
    private final PagoProperties props;
    private final MercadoPagoClient mercadoPago;
    private final MatriculaPagoService matriculaPago;

    /** A dónde debe ir el apoderado para pagar, y si la cuenta es de prueba o real. */
    public record PreferenciaVista(String codigo, String redireccion, String modo) {}

    @Transactional
    public PagoResponse pagarOnlineSimulado(RegistrarPagoRequest req, Long apoderadoUsuarioId) {
        // Con Mercado Pago activo, la demostración no puede registrar cobros en línea.
        if (props.getMercadopago().habilitado()) throw new BusinessRuleException("La pasarela de demostración está desactivada: paga con Mercado Pago.");
        exigirQueSeaDelApoderado(req, apoderadoUsuarioId);
        req.setMedio("ONLINE_SIMULADO");
        if (req.getReferencia() == null || req.getReferencia().isBlank()) {
            req.setReferencia("SIM-TX-" + System.currentTimeMillis());
        }
        // Ya no se confirma al instante: queda por aprobar en caja.
        return pagoService.solicitarPagoOnline(req, apoderadoUsuarioId);
    }

    /**
     * Pago de pensiones con Mercado Pago. No lanza la excepción dentro de la transacción salvo que la preferencia no se pueda crear (en ese caso el
     * pago se marca rechazado para que el apoderado pueda reintentar).
     */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public PreferenciaVista pagarConMercadoPago(RegistrarPagoRequest req, Long apoderadoUsuarioId) {
        PagoProperties.MercadoPago mp = props.getMercadopago();
        if (!mp.habilitado()) throw new BusinessRuleException("El pago con Mercado Pago no está activo en este servidor.");
        exigirQueSeaDelApoderado(req, apoderadoUsuarioId);
        req.setMedio("MERCADOPAGO");
        if (req.getReferencia() == null || req.getReferencia().isBlank()) req.setReferencia("MP-" + System.currentTimeMillis());

        PagoResponse creado = pagoService.solicitarPagoOnline(req, apoderadoUsuarioId);   // idempotente por clave
        Pago pago = pagoRepository.findById(creado.getId()).orElseThrow();
        if (pago.getPreferenciaUrl() == null) {
            try {
                String aviso = mp.getPublicBaseUrl() == null || mp.getPublicBaseUrl().isBlank() ? null
                    : mp.getPublicBaseUrl().replaceAll("/+$", "") + "/api/v1/pagos/pasarela/mercadopago/notificacion";
                String vuelta = mp.getFrontendUrl().replaceAll("/+$", "") + "/familia/pagos?pago=" + PREFIJO_REFERENCIA + pago.getId();
                var pref = mercadoPago.crearPreferencia(PREFIJO_REFERENCIA + pago.getId(), pago.getMontoTotal(),
                    "Pensiones EduSpace", pago.getPagadorNombre(), aviso, vuelta);
                pago.setPreferenciaId(pref.id());
                pago.setPreferenciaUrl(pref.initPoint());
                pagoRepository.save(pago);
            } catch (RuntimeException e) {
                log.error("No se creó la preferencia de Mercado Pago para el pago {}: {}", pago.getId(), e.getMessage());
                pagoService.rechazarPorPasarela(pago.getId(), "No se pudo iniciar el pago con Mercado Pago. Intenta de nuevo.");
                throw new BusinessRuleException("No se pudo iniciar el pago con Mercado Pago. Intenta de nuevo en unos minutos.");
            }
        }
        return new PreferenciaVista(PREFIJO_REFERENCIA + pago.getId(), pago.getPreferenciaUrl(), mp.modo());
    }

    /**
     * Pensiones pagadas en nuestra propia pantalla (tarjeta o Yape) con el token de MercadoPago.js: se registra el pago pendiente (reserva las
     * cuotas), se cobra por el monto que calcula el servidor y el resultado se aplica en el acto. Si Mercado Pago lo rechaza, el pago queda
     * rechazado y las cuotas libres para reintentar.
     */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public MatriculaPagoService.ResultadoMercadoPago pagarDirecto(RegistrarPagoRequest req, MercadoPagoClient.DatosCobro datos, Long apoderadoUsuarioId) {
        PagoProperties.MercadoPago mp = props.getMercadopago();
        if (!mp.habilitado()) throw new BusinessRuleException("El pago con Mercado Pago no está activo en este servidor.");
        if (datos == null || datos.token() == null || datos.token().isBlank() || datos.metodo() == null) {
            throw new BusinessRuleException("Faltan los datos del pago.");
        }
        if (datos.email() == null || !datos.email().contains("@")) throw new BusinessRuleException("Escribe un correo válido para el comprobante de Mercado Pago.");
        exigirQueSeaDelApoderado(req, apoderadoUsuarioId);
        req.setMedio("MERCADOPAGO");
        if (req.getReferencia() == null || req.getReferencia().isBlank()) req.setReferencia("MP-" + System.currentTimeMillis());

        PagoResponse creado = pagoService.solicitarPagoOnline(req, apoderadoUsuarioId);
        Pago pago = pagoRepository.findById(creado.getId()).orElseThrow();
        String referencia = PREFIJO_REFERENCIA + pago.getId();
        String aviso = mp.getPublicBaseUrl() == null || mp.getPublicBaseUrl().isBlank() ? null
            : mp.getPublicBaseUrl().replaceAll("/+$", "") + "/api/v1/pagos/pasarela/mercadopago/notificacion";
        MercadoPagoClient.Pago p;
        try {
            p = mercadoPago.cobrar(referencia, pago.getMontoTotal(), "Pensiones EduSpace", datos, aviso);
        } catch (BusinessRuleException e) {
            pagoService.rechazarPorPasarela(pago.getId(), e.getMessage());
            throw e;
        }
        log.info("Cobro {} de Mercado Pago ({}) para el pago de pensiones {}: {} {}", p.id(), datos.metodo(), pago.getId(), p.estado(), p.estadoDetalle());
        String ref = "Mercado Pago · " + datos.metodo() + " · Op. " + p.id();   // así sale en el comprobante
        pago.setReferencia(ref.length() > 80 ? ref.substring(0, 80) : ref);
        pagoRepository.save(pago);
        matriculaPago.procesarPagoMercadoPago(p);
        return matriculaPago.conComprobante(MatriculaPagoService.ResultadoMercadoPago.de(p), pago.getId());
    }

    /** Respaldo al volver de Mercado Pago: una sola consulta, y solo para el apoderado que hizo ese pago. */
    public void sincronizarPension(Long pagoId, Long usuarioId) {
        Pago pago = pagoRepository.findById(pagoId).orElseThrow(() -> new pe.edu.upeu.eduspace.pago.exception.ResourceNotFoundException("Pago no encontrado: " + pagoId));
        if (!usuarioId.equals(pago.getRegistradoPor())) throw new AccessDeniedException("Ese pago no es tuyo.");
        if (!"MERCADOPAGO".equals(pago.getMedio()) || !"PENDIENTE".equals(pago.getEstado())) return;
        matriculaPago.sincronizarReferencia(PREFIJO_REFERENCIA + pagoId);
    }

    /** El apoderado solo paga por sus hijos: el alumno declarado debe ser hijo suyo y todas las cuotas, de ese alumno. */
    private void exigirQueSeaDelApoderado(RegistrarPagoRequest req, Long apoderadoUsuarioId) {
        boolean esHijo = alumnosGateway.hijos(apoderadoUsuarioId).stream()
            .anyMatch(h -> h.getAlumnoId().equals(req.getAlumnoId()));
        boolean cuotasPropias = req.getDetalles().stream()
            .allMatch(d -> cuotaRepository.findById(d.getCuotaId()).map(c -> req.getAlumnoId().equals(c.getAlumnoId())).orElse(true));
        if (!esHijo || !cuotasPropias) {
            throw new AccessDeniedException("Solo puede pagar cuotas de sus propios hijos.");
        }
    }
}
