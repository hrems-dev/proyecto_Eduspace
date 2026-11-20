package pe.edu.upeu.eduspace.pago.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.eduspace.pago.dto.ComprobanteResponse;
import pe.edu.upeu.eduspace.pago.entity.Comprobante;
import pe.edu.upeu.eduspace.pago.entity.Pago;
import pe.edu.upeu.eduspace.pago.entity.SerieComprobante;
import pe.edu.upeu.eduspace.pago.exception.ResourceNotFoundException;
import pe.edu.upeu.eduspace.pago.repository.ComprobanteRepository;
import pe.edu.upeu.eduspace.pago.repository.SerieComprobanteRepository;
import pe.edu.upeu.eduspace.pago.client.PlanificacionClient;
import pe.edu.upeu.eduspace.pago.util.ComprobantePdf;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComprobanteService {

    private final ComprobanteRepository comprobanteRepository;
    private final SerieComprobanteRepository serieComprobanteRepository;
    private final MinioClient minioClient;
    private final PlanificacionClient planificacion;

    @Value("${app.minio.bucket-comprobantes:comprobantes}")
    private String bucketComprobantes;

    @Transactional
    public Comprobante emitirComprobante(Pago pago, String tipo) {
        String tipoFinal = (tipo != null && !tipo.isBlank()) ? tipo : "RECIBO";

        // Incremento atómico bajo concurrencia con SELECT ... FOR UPDATE
        SerieComprobante serieComp = serieComprobanteRepository.findFirstByTipoActivaForUpdate(tipoFinal)
            .orElseThrow(() -> new IllegalStateException("No existe una serie activa para tipo: " + tipoFinal));

        long nuevoNumero = serieComp.getUltimoNumero() + 1;
        serieComp.setUltimoNumero(nuevoNumero);
        serieComprobanteRepository.save(serieComp);

        String serie = serieComp.getSerie();
        String rutaPdf = String.format("%s/%s-%08d.pdf", tipoFinal.toLowerCase(), serie, nuevoNumero);

        Comprobante comprobante = Comprobante.builder()
            .pago(pago)
            .tipo(tipoFinal)
            .serie(serie)
            .numero(nuevoNumero)
            .total(pago.getMontoTotal())
            .estado("EMITIDO")
            .rutaPdf(rutaPdf)
            .emitidoEn(LocalDateTime.now())
            .build();

        Comprobante guardado = comprobanteRepository.save(comprobante);

        // Guardar representación de comprobante en MinIO
        subirAComprobanteMinio(guardado, pago);

        return guardado;
    }

    /** Comprobante ya emitido de un pago, o lo emite ahora si el pago está confirmado y aún no lo tiene (pagos anteriores a este cambio). */
    @Transactional
    public Comprobante obtenerOEmitir(Pago pago) {
        return comprobanteRepository.findByPagoId(pago.getId()).orElseGet(() -> {
            if (!"CONFIRMADO".equals(pago.getEstado())) {
                throw new pe.edu.upeu.eduspace.pago.exception.BusinessRuleException("El pago todavía no está confirmado: aún no tiene comprobante.");
            }
            return emitirComprobante(pago, pago.getTipoComprobante());
        });
    }

    /**
     * El PDF del comprobante (OpenPDF, ver {@link ComprobantePdf}). Se arma con los datos guardados, así se puede volver a descargar siempre
     * igual. Los datos del colegio, el grado y la sección se piden a planificación; si no responde, el PDF sale igual sin ellos.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(Comprobante c) {
        Pago p = c.getPago();
        String alumno = null, gradoSeccion = null;
        List<ComprobantePdf.Linea> lineas = new ArrayList<>();
        for (var d : p.getDetalles()) {
            var cuota = d.getCuota();
            var m = cuota.getMatricula();
            if (alumno == null && m != null) {
                alumno = m.getAlumnoNombre();
                gradoSeccion = gradoSeccion(m.getGradoId(), m.getSeccionId());
            }
            String concepto = cuota.getDescripcion() != null ? cuota.getDescripcion() : cuota.getConcepto().getNombre();
            lineas.add(new ComprobantePdf.Linea(concepto, cuota.getFechaVencimiento(), cuota.getMontoBase(), cuota.getDescuento(), cuota.getMora(),
                d.getMontoAplicado()));
        }
        if (lineas.isEmpty()) lineas.add(new ComprobantePdf.Linea("Pago", null, c.getTotal(), BigDecimal.ZERO, BigDecimal.ZERO, c.getTotal()));
        String ref = p.getReferencia() == null ? "" : p.getReferencia();
        Matcher op = Pattern.compile("Op\\.\\s*(\\S+)").matcher(ref);
        String operacion = op.find() ? op.group(1) : (ref.isBlank() || ref.startsWith("Matrícula pagada") ? null : ref);
        var datos = new ComprobantePdf.Datos(colegio(), c.getTipo(), String.format("%s-%08d", c.getSerie(), c.getNumero()), c.getEmitidoEn(),
            c.getEstado(), alumno != null ? alumno : "Alumno N.° " + p.getAlumnoId(), gradoSeccion, p.getPagadorNombre(), p.getPagadorDocumento(),
            medioLegible(p), operacion, p.getAprobadoEn() != null ? p.getAprobadoEn() : p.getPagadoEn(), lineas, c.getTotal());
        return ComprobantePdf.generar(datos);
    }

    /** «Yape (Mercado Pago)», «Efectivo»…: el medio como lo entiende la familia. */
    static String medioLegible(Pago p) {
        String ref = p.getReferencia() == null ? "" : p.getReferencia();
        if (ref.startsWith("Mercado Pago") || "MERCADOPAGO".equals(p.getMedio())) {
            String r = ref.toLowerCase();
            if (r.contains("yape")) return "Yape (Mercado Pago)";
            if (r.contains("pagoefectivo")) return "PagoEfectivo (Mercado Pago)";
            if (r.matches(".*(visa|master|amex|diners|deb).*")) return "Tarjeta (Mercado Pago)";
            return "Mercado Pago";
        }
        return switch (p.getMedio() == null ? "" : p.getMedio()) {
            case "EFECTIVO" -> "Efectivo";
            case "TARJETA" -> "Tarjeta";
            case "YAPE_PLIN" -> "Yape / Plin";
            case "TRANSFERENCIA" -> "Transferencia";
            case "CREDITO" -> "Crédito a favor del alumno";
            case "ONLINE_SIMULADO" -> "Pago en línea";
            default -> p.getMedio();
        };
    }

    // Los datos del colegio casi nunca cambian: se guardan 5 minutos para no consultar en cada comprobante.
    private volatile ComprobantePdf.Colegio colegioCache;
    private volatile long colegioCacheHasta;

    private ComprobantePdf.Colegio colegio() {
        if (colegioCache != null && System.currentTimeMillis() < colegioCacheHasta) return colegioCache;
        try {
            var c = planificacion.getColegio();
            if (c != null) {
                String direccion = String.join(", ", java.util.stream.Stream.of(c.direccion(), c.distrito(), c.provincia(), c.departamento())
                    .filter(s -> s != null && !s.isBlank()).toList());
                String contacto = String.join("  ·  ", java.util.stream.Stream.of(c.telefono() == null || c.telefono().isBlank() ? null : "Tel. " + c.telefono(),
                    c.email(), c.sitioWeb()).filter(s -> s != null && !s.isBlank()).toList());
                colegioCache = new ComprobantePdf.Colegio(c.nombre(), c.lema(), c.ruc(), c.codigoModular(), direccion, contacto);
                colegioCacheHasta = System.currentTimeMillis() + 5 * 60_000;
                return colegioCache;
            }
        } catch (RuntimeException e) {
            log.warn("No se obtuvieron los datos del colegio para el comprobante: {}", e.getMessage());
        }
        return colegioCache != null ? colegioCache : new ComprobantePdf.Colegio("EduSpace", null, null, null, null, null);
    }

    private String gradoSeccion(Long gradoId, Long seccionId) {
        try {
            var g = gradoId == null ? null : planificacion.getGrado(gradoId);
            var s = seccionId == null ? null : planificacion.getSeccion(seccionId);
            String grado = g == null ? null : (g.nivelNombre() != null ? g.nombre() + " de " + g.nivelNombre().toLowerCase() : g.nombre());
            if (grado == null) return null;
            String turno = s == null || s.turno() == null ? null : switch (s.turno().toUpperCase()) {
                case "MANANA", "MAÑANA" -> "mañana";
                case "TARDE" -> "tarde";
                case "NOCHE" -> "noche";
                default -> s.turno().toLowerCase();
            };
            return s == null ? grado : grado + " · sección " + s.nombre() + (turno != null ? " (" + turno + ")" : "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void subirAComprobanteMinio(Comprobante c, Pago p) {
        try {
            byte[] bytes = pdf(c);
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketComprobantes)
                    .object(c.getRutaPdf())
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/pdf")
                    .build()
            );
            log.info("Comprobante subido a MinIO en {}", c.getRutaPdf());
        } catch (Exception e) {
            log.warn("No se pudo subir comprobante a MinIO (se conserva registro en BD): {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Comprobante> buscarPorPago(Long pagoId) {
        return comprobanteRepository.findByPagoId(pagoId);
    }

    public ComprobanteResponse toResponse(Comprobante c) {
        return ComprobanteResponse.builder()
            .id(c.getId())
            .pagoId(c.getPago().getId())
            .tipo(c.getTipo())
            .serie(c.getSerie())
            .numero(c.getNumero())
            .numeroCompleto(String.format("%s-%08d", c.getSerie(), c.getNumero()))
            .total(c.getTotal())
            .estado(c.getEstado())
            .rutaPdf(c.getRutaPdf())
            .emitidoEn(c.getEmitidoEn())
            .build();
    }
}
