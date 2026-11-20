package pe.edu.upeu.eduspace.pago.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pe.edu.upeu.eduspace.pago.config.PagoProperties;
import pe.edu.upeu.eduspace.pago.security.UsuarioActual;
import pe.edu.upeu.eduspace.pago.service.MatriculaPagoService;
import pe.edu.upeu.eduspace.pago.service.MatriculaPagoService.*;
import pe.edu.upeu.eduspace.pago.util.FirmaWebhook;
import pe.edu.upeu.eduspace.pago.util.PasarelaSimulada;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pago de la matrícula con el ciclo de una pasarela real: el servidor calcula lo que falta y crea la orden; la pasarela (simulada) cobra y
 * avisa con su id de transacción; el servidor confirma una sola vez y suma el dinero al crédito, de donde se cobra la matrícula.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pagos")
@RequiredArgsConstructor
@Tag(name = "Pago de la matrícula", description = "Orden de pago, pasarela simulada, aviso firmado y generar la matrícula con el crédito")
public class MatriculaPagoController {

    private static final Set<String> PERSONAL = Set.of("ROLE_ADMIN", "ROLE_SECRETARIA", "ROLE_CAJERO");

    private final MatriculaPagoService servicio;
    private final PagoProperties props;
    private final ObjectMapper mapper;

    @GetMapping("/alumnos/{alumnoId}/matricula")
    @Operation(summary = "Cuánto falta de la matrícula, el crédito del alumno y si ya se puede generar (APODERADO de ese alumno, CAJERO, SECRETARIA, ADMIN)")
    public EstadoMatricula estado(@PathVariable Long alumnoId, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return servicio.estado(alumnoId, UsuarioActual.id(jwt), personal(auth));
    }

    @PostMapping("/alumnos/{alumnoId}/matricula/orden")
    @Operation(summary = "Crea la orden de pago por lo que falta de la matrícula; el monto lo calcula el servidor")
    public ResponseEntity<OrdenVista> crearOrden(@PathVariable Long alumnoId, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crearOrden(alumnoId, UsuarioActual.id(jwt), personal(auth)));
    }

    @PostMapping("/alumnos/{alumnoId}/matricula/generar")
    @Operation(summary = "Genera la matrícula cobrándola del crédito del alumno; falla si el crédito no alcanza")
    public EstadoMatricula generar(@PathVariable Long alumnoId, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return servicio.generar(alumnoId, UsuarioActual.id(jwt), personal(auth));
    }

    @PostMapping("/pasarela/ordenes/{codigo}/pagar")
    @Operation(summary = "Pantalla de pago de la pasarela SIMULADA: procesa el pago y avisa al servidor con el id de transacción")
    public ResultadoPasarela pagar(@PathVariable String codigo, @RequestBody PasarelaSimulada.Datos datos,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return servicio.pagarSimulado(codigo, datos, UsuarioActual.id(jwt), personal(auth));
    }

    @GetMapping("/pasarela/estado")
    @Operation(summary = "Qué pasarela de pago está activa (Mercado Pago real o la de demostración) y en qué modo")
    public Map<String, Object> estadoPasarela() {
        var mp = props.getMercadopago();
        // La clave pública es pública por diseño: MercadoPago.js la usa en el navegador para tokenizar la tarjeta o Yape.
        return Map.of("pasarela", mp.habilitado() ? "MERCADOPAGO" : "SIMULADA", "modo", mp.modo(),
            "publicKey", mp.habilitado() && mp.getPublicKey() != null ? mp.getPublicKey() : "");
    }

    @PostMapping("/pasarela/ordenes/{codigo}/mercadopago/directo")
    @Operation(summary = "Cobra la orden en nuestra pantalla (tarjeta o Yape) con el token de MercadoPago.js; el monto lo fija el servidor")
    public ResultadoMercadoPago pagarDirecto(@PathVariable String codigo, @RequestBody pe.edu.upeu.eduspace.pago.client.MercadoPagoClient.DatosCobro datos,
                                             @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return servicio.pagarDirecto(codigo, datos, UsuarioActual.id(jwt), personal(auth));
    }

    @PostMapping("/pasarela/ordenes/{codigo}/mercadopago")
    @Operation(summary = "Crea (o reutiliza) la preferencia de Mercado Pago de la orden y devuelve la URL donde la familia paga")
    public PreferenciaVista pagarConMercadoPago(@PathVariable String codigo, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return servicio.pagarConMercadoPago(codigo, UsuarioActual.id(jwt), personal(auth));
    }

    @PostMapping("/pasarela/ordenes/{codigo}/mercadopago/sincronizar")
    @Operation(summary = "Consulta a Mercado Pago los pagos de la orden y aplica los aprobados (al volver de la pantalla de pago)")
    public EstadoMatricula sincronizarMercadoPago(@PathVariable String codigo, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return servicio.sincronizarMercadoPago(codigo, UsuarioActual.id(jwt), personal(auth));
    }

    /**
     * El aviso (webhook) de Mercado Pago. Sin sesión: Mercado Pago lo envía desde sus servidores. Si hay clave secreta configurada se verifica la
     * firma x-signature; en cualquier caso el pago se vuelve a consultar al API antes de confirmar nada. Responde 200 cuando el aviso se procesó
     * (aunque no cambie nada) para que Mercado Pago no lo reintente sin fin.
     */
    @PostMapping(value = "/pasarela/mercadopago/notificacion", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Aviso de Mercado Pago (pago creado o actualizado): confirma la orden si el pago está aprobado")
    public ResponseEntity<Map<String, String>> avisoMercadoPago(@RequestBody(required = false) String cuerpo,
                                                                @RequestParam(value = "data.id", required = false) String dataIdQuery,
                                                                @RequestParam(value = "type", required = false) String tipoQuery,
                                                                @RequestHeader(value = "x-signature", required = false) String firma,
                                                                @RequestHeader(value = "x-request-id", required = false) String requestId) {
        Map<String, Object> cuerpoMapa = cuerpo == null || cuerpo.isBlank() ? Map.of() : mapper.readValue(cuerpo, Map.class);
        String tipo = tipoQuery != null ? tipoQuery : texto(cuerpoMapa.get("type"));
        String dataId = dataIdQuery != null ? dataIdQuery : texto(cuerpoMapa.get("data.id"));
        if (dataId == null && cuerpoMapa.get("data") instanceof Map<?, ?> d) dataId = texto(d.get("id"));
        if (!"payment".equals(tipo) || dataId == null || !dataId.matches("\\d{1,18}")) {
            return ResponseEntity.ok(Map.of("estado", "ignorado"));
        }
        String secreto = props.getMercadopago().getWebhookSecret();
        if (secreto != null && !secreto.isBlank()) {
            String[] partes = firma == null ? new String[0] : firma.split(",");
            String ts = null, v1 = null;
            for (String p : partes) {
                String[] kv = p.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("ts")) ts = kv[1];
                if (kv.length == 2 && kv[0].equals("v1")) v1 = kv[1];
            }
            String manifiesto = "id:" + dataId + ";request-id:" + (requestId == null ? "" : requestId) + ";ts:" + (ts == null ? "" : ts) + ";";
            if (ts == null || v1 == null || !FirmaWebhook.valida(secreto, manifiesto, v1)) {
                log.warn("Aviso de Mercado Pago con firma inválida descartado.");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Firma inválida"));
            }
        }
        servicio.procesarAvisoMercadoPago(Long.parseLong(dataId));
        return ResponseEntity.ok(Map.of("estado", "procesado"));
    }

    private static String texto(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * El aviso (webhook) de la pasarela. No lleva sesión: lo protege la firma HMAC-SHA256 del cuerpo en la cabecera {@code X-Firma}
     * (el navegador no tiene el secreto, así que no puede confirmar una orden por su cuenta).
     */
    @PostMapping(value = "/pasarela/notificacion", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Aviso firmado de la pasarela: confirma la orden (idempotente)")
    public ResponseEntity<Map<String, String>> notificacion(@RequestBody String cuerpo, @RequestHeader(value = "X-Firma", required = false) String firma) {
        if (!FirmaWebhook.valida(props.getPasarelaSecreto(), cuerpo, firma)) {
            log.warn("Aviso de la pasarela con firma inválida descartado.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Firma inválida"));
        }
        Aviso aviso = mapper.readValue(cuerpo, Aviso.class);
        var orden = servicio.confirmarOrden(aviso);
        return ResponseEntity.ok(Map.of("orden", orden.getCodigo(), "estado", orden.getEstado()));
    }

    @GetMapping("/matriculas/por-pagar")
    @Operation(summary = "Alumnos con pre-matrícula que aún deben la matrícula (CAJERO, SECRETARIA, ADMIN)")
    public List<PorPagar> porPagar() {
        return servicio.porPagar();
    }

    private static boolean personal(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> PERSONAL.contains(a.getAuthority()));
    }
}
