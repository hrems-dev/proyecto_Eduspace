package pe.edu.upeu.eduspace.pago.util;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Comprobante de pago en PDF con OpenPDF: cabecera con los datos del colegio, número y fecha, estado, datos del alumno y del pago,
 * detalle por concepto, totales, monto en letras, código de barras y código de verificación. Si el comprobante está ANULADO lleva una
 * marca de agua. Todo el texto usa fuentes estándar (Helvetica, WinAnsi): tildes y ñ sí, emojis no.
 */
public final class ComprobantePdf {

    private ComprobantePdf() {}

    // ---------------------------------------------------------------- datos de entrada

    public record Colegio(String nombre, String lema, String ruc, String codigoModular, String direccion, String contacto) {}

    public record Linea(String concepto, LocalDate vence, BigDecimal importe, BigDecimal descuento, BigDecimal mora, BigDecimal pagado) {}

    public record Datos(Colegio colegio, String tipo, String numero, LocalDateTime emitidoEn, String estado,
                        String alumno, String gradoSeccion, String pagador, String pagadorDocumento,
                        String medio, String operacion, LocalDateTime pagadoEn, List<Linea> lineas, BigDecimal total) {}

    // ---------------------------------------------------------------- estilo

    private static final Color PRIMARIO = new Color(0x1F, 0x3A, 0x93);
    private static final Color PRIMARIO_CLARO = new Color(0xEE, 0xF2, 0xFF);
    private static final Color OK = new Color(0x05, 0x7A, 0x55);
    private static final Color OK_CLARO = new Color(0xE8, 0xF7, 0xEF);
    private static final Color PELIGRO = new Color(0xC8, 0x1E, 0x1E);
    private static final Color TEXTO = new Color(0x1F, 0x29, 0x37);
    private static final Color GRIS = new Color(0x6B, 0x72, 0x80);
    private static final Color BORDE = new Color(0xD9, 0xDE, 0xE7);
    private static final Color ZEBRA = new Color(0xF8, 0xFA, 0xFC);

    private static Font f(float tam, int estilo, Color color) {
        return new Font(Font.HELVETICA, tam, estilo, color);
    }

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ---------------------------------------------------------------- generación

    public static byte[] generar(Datos d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 70);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            boolean anulado = "ANULADO".equalsIgnoreCase(d.estado());
            writer.setPageEvent(new Pie(d, anulado));
            doc.addTitle("Comprobante " + d.numero());
            doc.addAuthor(nvl(d.colegio().nombre(), "EduSpace"));
            doc.addCreator("EduSpace");
            doc.open();

            doc.add(cabecera(writer, d));
            doc.add(barraEstado(d, anulado));
            doc.add(datos(d));
            doc.add(detalle(d));
            doc.add(totales(d));
            doc.add(enLetras(d));
            doc.add(verificacion(writer, d));
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("No se pudo generar el PDF del comprobante: " + e.getMessage(), e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return out.toByteArray();
    }

    /** Logo (círculo con las iniciales), datos del colegio y el recuadro con tipo, número y fecha. */
    private static PdfPTable cabecera(PdfWriter writer, Datos d) throws DocumentException, java.io.IOException {
        PdfPTable t = new PdfPTable(new float[]{58, 42});
        t.setWidthPercentage(100);

        // Izquierda: logo + colegio
        PdfPTable izq = new PdfPTable(new float[]{16, 84});
        izq.setWidthPercentage(100);
        PdfPCell logo = new PdfPCell(logo(writer, d.colegio().nombre()), false);
        logo.setBorder(Rectangle.NO_BORDER);
        logo.setVerticalAlignment(Element.ALIGN_TOP);
        logo.setPaddingTop(2);
        izq.addCell(logo);

        Paragraph col = new Paragraph();
        col.add(new Chunk(nvl(d.colegio().nombre(), "Colegio") + "\n", f(14, Font.BOLD, PRIMARIO)));
        if (tiene(d.colegio().lema())) col.add(new Chunk("«" + d.colegio().lema() + "»\n", f(8, Font.ITALIC, GRIS)));
        StringBuilder sub = new StringBuilder();
        if (tiene(d.colegio().ruc())) sub.append("RUC ").append(d.colegio().ruc());
        if (tiene(d.colegio().codigoModular())) sub.append(sub.isEmpty() ? "" : "  ·  ").append("Cód. modular ").append(d.colegio().codigoModular());
        if (!sub.isEmpty()) col.add(new Chunk(sub + "\n", f(8, Font.NORMAL, GRIS)));
        if (tiene(d.colegio().direccion())) col.add(new Chunk(d.colegio().direccion() + "\n", f(8, Font.NORMAL, GRIS)));
        if (tiene(d.colegio().contacto())) col.add(new Chunk(d.colegio().contacto(), f(8, Font.NORMAL, GRIS)));
        col.setLeading(11);
        PdfPCell datosCol = new PdfPCell();
        datosCol.addElement(col);
        datosCol.setBorder(Rectangle.NO_BORDER);
        datosCol.setPaddingLeft(6);
        izq.addCell(datosCol);

        PdfPCell celIzq = new PdfPCell(izq);
        celIzq.setBorder(Rectangle.NO_BORDER);
        t.addCell(celIzq);

        // Derecha: recuadro del documento
        PdfPTable der = new PdfPTable(1);
        der.setWidthPercentage(100);
        String titulo = "BOLETA".equalsIgnoreCase(d.tipo()) ? "BOLETA DE VENTA" : "RECIBO DE PAGO";
        PdfPCell tit = new PdfPCell(new Phrase(titulo, f(11, Font.BOLD, Color.WHITE)));
        tit.setBackgroundColor(PRIMARIO);
        tit.setBorderColor(PRIMARIO);
        tit.setHorizontalAlignment(Element.ALIGN_CENTER);
        tit.setPadding(7);
        der.addCell(tit);
        Paragraph num = new Paragraph();
        num.setAlignment(Element.ALIGN_CENTER);
        num.add(new Chunk("N.° " + d.numero() + "\n", f(15, Font.BOLD, TEXTO)));
        num.add(new Chunk("Emitido el " + d.emitidoEn().format(FECHA_HORA), f(8.5f, Font.NORMAL, GRIS)));
        PdfPCell n = new PdfPCell();
        n.addElement(num);
        n.setBorderColor(PRIMARIO);
        n.setBorderWidth(1.2f);
        n.setPaddingTop(4);
        n.setPaddingBottom(9);
        der.addCell(n);

        PdfPCell celDer = new PdfPCell(der);
        celDer.setBorder(Rectangle.NO_BORDER);
        celDer.setPaddingLeft(14);
        t.addCell(celDer);
        t.setSpacingAfter(14);
        return t;
    }

    /** Círculo de color con las iniciales del colegio (no hace falta un archivo de imagen). */
    private static Image logo(PdfWriter writer, String nombre) throws DocumentException, java.io.IOException {
        PdfTemplate tpl = writer.getDirectContent().createTemplate(56, 56);
        tpl.setColorFill(PRIMARIO);
        tpl.circle(28, 28, 27);
        tpl.fill();
        tpl.setColorStroke(Color.WHITE);
        tpl.setLineWidth(1.5f);
        tpl.circle(28, 28, 23);
        tpl.stroke();
        BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        tpl.beginText();
        tpl.setColorFill(Color.WHITE);
        tpl.setFontAndSize(bf, 17);
        tpl.showTextAligned(PdfContentByte.ALIGN_CENTER, iniciales(nombre), 28, 22, 0);
        tpl.endText();
        Image img = Image.getInstance(tpl);
        img.scaleToFit(52, 52);
        return img;
    }

    /** Franja con el estado: PAGADO (verde) o ANULADO (rojo), y cuándo se pagó. */
    private static PdfPTable barraEstado(Datos d, boolean anulado) {
        PdfPTable t = new PdfPTable(new float[]{18, 82});
        t.setWidthPercentage(100);
        PdfPCell pill = new PdfPCell(new Phrase(anulado ? "ANULADO" : "PAGADO", f(10, Font.BOLD, Color.WHITE)));
        pill.setBackgroundColor(anulado ? PELIGRO : OK);
        pill.setBorder(Rectangle.NO_BORDER);
        pill.setHorizontalAlignment(Element.ALIGN_CENTER);
        pill.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pill.setPadding(7);
        t.addCell(pill);
        String texto = anulado ? "Este comprobante fue anulado y no acredita ningún pago."
            : "Pago confirmado el " + (d.pagadoEn() != null ? d.pagadoEn().format(FECHA_HORA) : d.emitidoEn().format(FECHA_HORA))
              + (tiene(d.operacion()) ? "   ·   Operación N.° " + d.operacion() : "");
        PdfPCell txt = new PdfPCell(new Phrase(texto, f(9.5f, Font.BOLD, anulado ? PELIGRO : OK)));
        txt.setBackgroundColor(anulado ? new Color(0xFD, 0xEC, 0xEC) : OK_CLARO);
        txt.setBorder(Rectangle.NO_BORDER);
        txt.setVerticalAlignment(Element.ALIGN_MIDDLE);
        txt.setPaddingLeft(10);
        t.addCell(txt);
        t.setSpacingAfter(14);
        return t;
    }

    /** Dos recuadros: datos del alumno y datos del pago. */
    private static PdfPTable datos(Datos d) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.addCell(recuadro("DATOS DEL ALUMNO", new String[][]{
            {"Alumno", nvl(d.alumno(), "-")},
            {"Grado y sección", nvl(d.gradoSeccion(), "-")},
            {"Pagado por", nvl(d.pagador(), "-") + (tiene(d.pagadorDocumento()) ? " (" + d.pagadorDocumento() + ")" : "")}
        }, 0, 6));
        t.addCell(recuadro("DATOS DEL PAGO", new String[][]{
            {"Medio de pago", nvl(d.medio(), "-")},
            {"N.° de operación", nvl(d.operacion(), "-")},
            {"Fecha de pago", d.pagadoEn() != null ? d.pagadoEn().format(FECHA_HORA) : "-"}
        }, 6, 0));
        t.setSpacingAfter(16);
        return t;
    }

    private static PdfPCell recuadro(String titulo, String[][] filas, float padIzq, float padDer) {
        PdfPTable in = new PdfPTable(new float[]{38, 62});
        in.setWidthPercentage(100);
        PdfPCell h = new PdfPCell(new Phrase(titulo, f(8.5f, Font.BOLD, PRIMARIO)));
        h.setColspan(2);
        h.setBackgroundColor(PRIMARIO_CLARO);
        h.setBorder(Rectangle.NO_BORDER);
        h.setPadding(6);
        in.addCell(h);
        for (String[] fila : filas) {
            PdfPCell k = new PdfPCell(new Phrase(fila[0], f(8.5f, Font.NORMAL, GRIS)));
            k.setBorder(Rectangle.NO_BORDER);
            k.setPadding(5);
            in.addCell(k);
            PdfPCell v = new PdfPCell(new Phrase(fila[1], f(9, Font.BOLD, TEXTO)));
            v.setBorder(Rectangle.NO_BORDER);
            v.setPadding(5);
            in.addCell(v);
        }
        PdfPCell c = new PdfPCell(in);
        c.setBorderColor(BORDE);
        c.setPadding(0);
        PdfPCell envoltura = new PdfPCell();
        envoltura.setBorder(Rectangle.NO_BORDER);
        envoltura.setPaddingLeft(padIzq);
        envoltura.setPaddingRight(padDer);
        PdfPTable caja = new PdfPTable(1);
        caja.setWidthPercentage(100);
        caja.addCell(c);
        envoltura.addElement(caja);
        return envoltura;
    }

    /** Tabla del detalle: un renglón por concepto pagado. */
    private static PdfPTable detalle(Datos d) {
        boolean hayMora = d.lineas().stream().anyMatch(l -> positivo(l.mora()));
        boolean hayDesc = d.lineas().stream().anyMatch(l -> positivo(l.descuento()));
        java.util.List<Float> anchos = new java.util.ArrayList<>(List.of(6f, 40f, 14f, 13f));
        if (hayDesc) anchos.add(12f);
        if (hayMora) anchos.add(10f);
        anchos.add(14f);
        float[] w = new float[anchos.size()];
        for (int i = 0; i < w.length; i++) w[i] = anchos.get(i);
        PdfPTable t = new PdfPTable(w);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);

        java.util.List<String> cab = new java.util.ArrayList<>(List.of("#", "Concepto", "Vence", "Importe"));
        if (hayDesc) cab.add("Descuento");
        if (hayMora) cab.add("Mora");
        cab.add("Pagado");
        for (int i = 0; i < cab.size(); i++) {
            PdfPCell c = new PdfPCell(new Phrase(cab.get(i), f(8.5f, Font.BOLD, Color.WHITE)));
            c.setBackgroundColor(PRIMARIO);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(6);
            c.setHorizontalAlignment(i >= 3 ? Element.ALIGN_RIGHT : (i == 0 ? Element.ALIGN_CENTER : Element.ALIGN_LEFT));
            t.addCell(c);
        }
        int n = 0;
        for (Linea l : d.lineas()) {
            n++;
            Color fondo = n % 2 == 0 ? ZEBRA : Color.WHITE;
            t.addCell(celdaDetalle(String.valueOf(n), Element.ALIGN_CENTER, fondo, false));
            t.addCell(celdaDetalle(nvl(l.concepto(), "Pago"), Element.ALIGN_LEFT, fondo, false));
            t.addCell(celdaDetalle(l.vence() != null ? l.vence().format(FECHA) : "-", Element.ALIGN_LEFT, fondo, false));
            t.addCell(celdaDetalle(soles(l.importe()), Element.ALIGN_RIGHT, fondo, false));
            if (hayDesc) t.addCell(celdaDetalle(positivo(l.descuento()) ? "- " + soles(l.descuento()) : "-", Element.ALIGN_RIGHT, fondo, false));
            if (hayMora) t.addCell(celdaDetalle(positivo(l.mora()) ? soles(l.mora()) : "-", Element.ALIGN_RIGHT, fondo, false));
            t.addCell(celdaDetalle(soles(l.pagado()), Element.ALIGN_RIGHT, fondo, true));
        }
        t.setSpacingAfter(10);
        return t;
    }

    private static PdfPCell celdaDetalle(String texto, int alineacion, Color fondo, boolean negrita) {
        PdfPCell c = new PdfPCell(new Phrase(texto, f(9, negrita ? Font.BOLD : Font.NORMAL, TEXTO)));
        c.setBackgroundColor(fondo);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDE);
        c.setPadding(6);
        c.setHorizontalAlignment(alineacion);
        return c;
    }

    /** Recuadro de totales alineado a la derecha. */
    private static PdfPTable totales(Datos d) {
        BigDecimal importe = suma(d, Linea::importe);
        BigDecimal descuento = suma(d, Linea::descuento);
        BigDecimal mora = suma(d, Linea::mora);
        PdfPTable t = new PdfPTable(new float[]{55, 25, 20});
        t.setWidthPercentage(100);
        // El desglose solo aparece si suma algo: sin descuentos ni mora, el importe sería igual al total.
        if (positivo(descuento) || positivo(mora)) {
            fila(t, "Importe", soles(importe), false);
            if (positivo(descuento)) fila(t, "Descuentos", "- " + soles(descuento), false);
            if (positivo(mora)) fila(t, "Mora", soles(mora), false);
        }

        PdfPCell vacia = new PdfPCell();
        vacia.setBorder(Rectangle.NO_BORDER);
        t.addCell(vacia);
        PdfPCell k = new PdfPCell(new Phrase("TOTAL PAGADO", f(10.5f, Font.BOLD, Color.WHITE)));
        k.setBackgroundColor(PRIMARIO);
        k.setBorder(Rectangle.NO_BORDER);
        k.setPadding(8);
        t.addCell(k);
        PdfPCell v = new PdfPCell(new Phrase("S/ " + soles(d.total()), f(12.5f, Font.BOLD, Color.WHITE)));
        v.setBackgroundColor(PRIMARIO);
        v.setBorder(Rectangle.NO_BORDER);
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        v.setPadding(8);
        t.addCell(v);
        t.setSpacingAfter(8);
        return t;
    }

    private static void fila(PdfPTable t, String etiqueta, String valor, boolean negrita) {
        PdfPCell vacia = new PdfPCell();
        vacia.setBorder(Rectangle.NO_BORDER);
        t.addCell(vacia);
        PdfPCell k = new PdfPCell(new Phrase(etiqueta, f(9, Font.NORMAL, GRIS)));
        k.setBorder(Rectangle.NO_BORDER);
        k.setPadding(4);
        t.addCell(k);
        PdfPCell v = new PdfPCell(new Phrase(valor, f(9, negrita ? Font.BOLD : Font.NORMAL, TEXTO)));
        v.setBorder(Rectangle.NO_BORDER);
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        v.setPadding(4);
        t.addCell(v);
    }

    private static Paragraph enLetras(Datos d) {
        Paragraph p = new Paragraph();
        p.add(new Chunk("SON: ", f(9, Font.BOLD, TEXTO)));
        p.add(new Chunk(montoEnLetras(d.total()), f(9, Font.NORMAL, TEXTO)));
        p.setSpacingAfter(22);
        return p;
    }

    /** Código de barras del número, código de verificación y agradecimiento. */
    private static PdfPTable verificacion(PdfWriter writer, Datos d) {
        PdfPTable t = new PdfPTable(new float[]{45, 55});
        t.setWidthPercentage(100);
        Barcode128 b = new Barcode128();
        b.setCode(d.numero());
        b.setBarHeight(30);
        b.setX(0.9f);
        b.setFont(null);   // sin texto debajo: el número va escrito al lado
        Image img = b.createImageWithBarcode(writer.getDirectContent(), TEXTO, TEXTO);
        PdfPCell bc = new PdfPCell();
        bc.setBorder(Rectangle.NO_BORDER);
        bc.addElement(img);
        Paragraph cod = new Paragraph();
        cod.add(new Chunk("Código de verificación: ", f(8, Font.NORMAL, GRIS)));
        cod.add(new Chunk(codigoVerificacion(d), f(8, Font.BOLD, TEXTO)));
        bc.addElement(cod);
        t.addCell(bc);

        Paragraph gr = new Paragraph();
        gr.setAlignment(Element.ALIGN_RIGHT);
        gr.add(new Chunk("¡Gracias por su pago!\n", f(12, Font.BOLD, PRIMARIO)));
        gr.add(new Chunk("Conserve este comprobante. Puede descargarlo de nuevo\ncuando quiera desde el portal, en Pagos.", f(8.5f, Font.NORMAL, GRIS)));
        PdfPCell g = new PdfPCell();
        g.setBorder(Rectangle.NO_BORDER);
        g.addElement(gr);
        g.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(g);
        t.setKeepTogether(true);
        return t;
    }

    /** Pie de página (y marca de agua si está anulado) en cada hoja. */
    private static final class Pie extends PdfPageEventHelper {
        private final Datos d;
        private final boolean anulado;

        Pie(Datos d, boolean anulado) {
            this.d = d;
            this.anulado = anulado;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float izq = document.left(), der = document.right(), y = document.bottom() - 22;
            cb.setColorStroke(BORDE);
            cb.setLineWidth(0.8f);
            cb.moveTo(izq, y + 12);
            cb.lineTo(der, y + 12);
            cb.stroke();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase("Comprobante interno de pago de " + nvl(d.colegio().nombre(), "la institución")
                + ". No reemplaza al comprobante electrónico de SUNAT.", f(7, Font.NORMAL, GRIS)), izq, y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase("Generado por EduSpace el " + LocalDateTime.now().format(FECHA_HORA),
                f(7, Font.NORMAL, GRIS)), izq, y - 10, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(d.numero() + "  ·  Página " + writer.getPageNumber(),
                f(7, Font.BOLD, GRIS)), der, y, 0);
            if (anulado) {
                PdfContentByte under = writer.getDirectContentUnder();
                under.saveState();
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.12f);
                under.setGState(gs);
                ColumnText.showTextAligned(under, Element.ALIGN_CENTER, new Phrase("ANULADO", f(110, Font.BOLD, PELIGRO)),
                    (document.left() + document.right()) / 2, (document.top() + document.bottom()) / 2, 45);
                under.restoreState();
            }
        }
    }

    // ---------------------------------------------------------------- utilidades

    private static BigDecimal suma(Datos d, java.util.function.Function<Linea, BigDecimal> campo) {
        return d.lineas().stream().map(campo).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean positivo(BigDecimal b) {
        return b != null && b.signum() > 0;
    }

    private static String soles(BigDecimal b) {
        return String.format(Locale.US, "%,.2f", b == null ? BigDecimal.ZERO : b);
    }

    private static boolean tiene(String s) {
        return s != null && !s.isBlank();
    }

    private static String nvl(String s, String porDefecto) {
        return tiene(s) ? s : porDefecto;
    }

    static String iniciales(String nombre) {
        if (!tiene(nombre)) return "ES";
        StringBuilder sb = new StringBuilder();
        for (String p : nombre.trim().split("\\s+")) {
            if (p.length() > 2 && Character.isLetter(p.charAt(0)) && !List.of("del", "de", "la", "los", "las", "el").contains(p.toLowerCase())) {
                sb.append(Character.toUpperCase(p.charAt(0)));
            }
            if (sb.length() == 2) break;
        }
        return sb.isEmpty() ? "ES" : sb.toString();
    }

    /** Huella corta del comprobante (número, total y fecha de emisión): si alguien edita el PDF, ya no coincide con el sistema. */
    static String codigoVerificacion(Datos d) {
        try {
            String base = d.numero() + "|" + d.total().setScale(2, RoundingMode.HALF_UP) + "|" + d.emitidoEn().withNano(0);
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8)))
                .toUpperCase();
            return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- monto en letras (como en los comprobantes peruanos)

    private static final String[] UNIDADES = {"", "UN", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE", "DIEZ",
        "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISÉIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE", "VEINTE",
        "VEINTIUN", "VEINTIDÓS", "VEINTITRÉS", "VEINTICUATRO", "VEINTICINCO", "VEINTISÉIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE"};
    private static final String[] DECENAS = {"", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] CENTENAS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS", "SEISCIENTOS",
        "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    /** 1234.50 → "MIL DOSCIENTOS TREINTA Y CUATRO CON 50/100 SOLES". */
    public static String montoEnLetras(BigDecimal monto) {
        BigDecimal m = (monto == null ? BigDecimal.ZERO : monto).setScale(2, RoundingMode.HALF_UP);
        long entero = m.longValue();
        int centimos = m.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
        String letras = entero == 0 ? "CERO" : letras(entero);
        return letras + String.format(" CON %02d/100 %s", centimos, entero == 1 ? "SOL" : "SOLES");
    }

    private static String letras(long n) {
        if (n >= 1_000_000) {
            long millones = n / 1_000_000, resto = n % 1_000_000;
            String s = millones == 1 ? "UN MILLÓN" : letras(millones) + " MILLONES";
            return resto == 0 ? s : s + " " + letras(resto);
        }
        if (n >= 1000) {
            long miles = n / 1000, resto = n % 1000;
            String s = miles == 1 ? "MIL" : letras(miles) + " MIL";
            return resto == 0 ? s : s + " " + letras(resto);
        }
        if (n >= 100) {
            if (n == 100) return "CIEN";
            long resto = n % 100;
            return CENTENAS[(int) (n / 100)] + (resto == 0 ? "" : " " + letras(resto));
        }
        if (n < 30) return UNIDADES[(int) n];
        long u = n % 10;
        return DECENAS[(int) (n / 10)] + (u == 0 ? "" : " Y " + UNIDADES[(int) u]);
    }
}
