package pe.edu.upeu.eduspace.pago.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComprobantePdfTest {

    private static ComprobantePdf.Datos datos(String estado, List<ComprobantePdf.Linea> lineas, BigDecimal total) {
        return new ComprobantePdf.Datos(
            new ComprobantePdf.Colegio("Colegio Horizonte del Valle", "Educar con valores", "20123456789", "0123456",
                "Av. Los Álamos 123, Juliaca, Puno", "Tel. 051-123456  ·  info@horizonte.edu.pe"),
            "RECIBO", "R001-00000001", LocalDateTime.of(2026, 11, 20, 10, 7), estado,
            "chambilla robles, juan pers", "1.° de primaria · sección A (mañana)", "Apoderado", "DNI 12457896",
            "Yape (Mercado Pago)", "190705031526", LocalDateTime.of(2026, 11, 20, 9, 45), lineas, total);
    }

    @Test
    void generaUnPdfValidoConVariasLineasDescuentoYMora() {
        var lineas = List.of(
            new ComprobantePdf.Linea("Matrícula escolar 2026", LocalDate.of(2026, 12, 18), new BigDecimal("350.00"), new BigDecimal("35.00"),
                BigDecimal.ZERO, new BigDecimal("315.00")),
            new ComprobantePdf.Linea("Pensión marzo 2026 (cuota 1 de 10)", LocalDate.of(2026, 3, 31), new BigDecimal("400.00"), BigDecimal.ZERO,
                new BigDecimal("8.00"), new BigDecimal("408.00")));
        byte[] pdf = ComprobantePdf.generar(datos("EMITIDO", lineas, new BigDecimal("723.00")));

        assertThat(pdf.length).isGreaterThan(2000);
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void generaElComprobanteAnuladoConMarcaDeAgua() {
        var lineas = List.of(new ComprobantePdf.Linea("Matrícula", null, new BigDecimal("2.00"), null, null, new BigDecimal("2.00")));
        byte[] pdf = ComprobantePdf.generar(datos("ANULADO", lineas, new BigDecimal("2.00")));
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    void escribeElMontoEnLetras() {
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("2.00"))).isEqualTo("DOS CON 00/100 SOLES");
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("1.00"))).isEqualTo("UN CON 00/100 SOL");
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("0.50"))).isEqualTo("CERO CON 50/100 SOLES");
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("100"))).isEqualTo("CIEN CON 00/100 SOLES");
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("121.30"))).isEqualTo("CIENTO VEINTIUN CON 30/100 SOLES");
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("1234.56"))).isEqualTo("MIL DOSCIENTOS TREINTA Y CUATRO CON 56/100 SOLES");
        assertThat(ComprobantePdf.montoEnLetras(new BigDecimal("2500000"))).isEqualTo("DOS MILLONES QUINIENTOS MIL CON 00/100 SOLES");
    }

    @Test
    void sacaLasInicialesDelColegio() {
        assertThat(ComprobantePdf.iniciales("Colegio Horizonte del Valle")).isEqualTo("CH");
        assertThat(ComprobantePdf.iniciales(null)).isEqualTo("ES");
    }
}
