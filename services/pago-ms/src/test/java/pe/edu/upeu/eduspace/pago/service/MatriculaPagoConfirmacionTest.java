package pe.edu.upeu.eduspace.pago.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.edu.upeu.eduspace.pago.client.AlumnosGateway;
import pe.edu.upeu.eduspace.pago.client.MercadoPagoClient;
import pe.edu.upeu.eduspace.pago.client.PlanificacionClient;
import pe.edu.upeu.eduspace.pago.config.PagoProperties;
import pe.edu.upeu.eduspace.pago.entity.OrdenPago;
import pe.edu.upeu.eduspace.pago.repository.CuotaRepository;
import pe.edu.upeu.eduspace.pago.repository.MatriculaLocalRepository;
import pe.edu.upeu.eduspace.pago.repository.OrdenPagoRepository;
import pe.edu.upeu.eduspace.pago.repository.SolicitudDescuentoRepository;
import pe.edu.upeu.eduspace.pago.service.MatriculaPagoService.Aviso;

/** Confirmación de una orden con el aviso de la pasarela: monto, cobro de la matrícula y que no se pierda el dinero. */
class MatriculaPagoConfirmacionTest {

    private final OrdenPagoRepository ordenes = mock(OrdenPagoRepository.class);
    private final CreditoService credito = mock(CreditoService.class);
    private final MatriculaPagoService servicio = new MatriculaPagoService(mock(CuotaRepository.class), mock(MatriculaLocalRepository.class),
        ordenes, mock(SolicitudDescuentoRepository.class), credito, mock(AlumnosGateway.class), new PagoProperties(),
        mock(PlanificacionClient.class), mock(MercadoPagoClient.class), mock(PagoService.class), mock(pe.edu.upeu.eduspace.pago.repository.PagoRepository.class), mock(ComprobanteService.class));

    private OrdenPago orden() {
        return OrdenPago.builder().codigo("ORD-1").alumnoId(7L).matriculaId(9L).monto(new BigDecimal("150.00"))
            .expiraEn(LocalDateTime.now().plusMinutes(30)).estado(OrdenPago.PENDIENTE).build();
    }

    private static Aviso aprobado(String monto) {
        return new Aviso("ORD-1", "tx-1", "APROBADO", new BigDecimal(monto), "TARJETA", "Ana", "accredited", "Mercado Pago · visa");
    }

    @Test
    void montoDistintoNoConfirmaNiCierraLaOrden() {
        OrdenPago o = orden();
        when(ordenes.bloquearPorCodigo("ORD-1")).thenReturn(Optional.of(o));

        servicio.confirmarOrden(aprobado("100.00"));   // no debe lanzar

        assertThat(o.getEstado()).isEqualTo(OrdenPago.PENDIENTE);
        assertThat(o.getRespuesta()).contains("ALERTA");
        verify(credito, never()).depositarSinCobro(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void siLaMatriculaNoSePuedeCobrarElDepositoQuedaComoCreditoYLaOrdenSeAprueba() {
        OrdenPago o = orden();
        when(ordenes.bloquearPorCodigo("ORD-1")).thenReturn(Optional.of(o));
        when(credito.matriculaCobrable(7L, 9L)).thenReturn(false);

        servicio.confirmarOrden(aprobado("150.00"));

        assertThat(o.getEstado()).isEqualTo(OrdenPago.APROBADA);
        verify(credito).depositarSinCobro(any(), any(), any(), any(), any(), any());
        verify(credito, never()).cobrarMatricula(any(), any(), any());
    }

    @Test
    void siLaMatriculaSePuedeCobrarSeCobraDelCredito() {
        OrdenPago o = orden();
        when(ordenes.bloquearPorCodigo("ORD-1")).thenReturn(Optional.of(o));
        when(credito.matriculaCobrable(7L, 9L)).thenReturn(true);
        when(credito.cobrarMatricula(anyLong(), anyLong(), any())).thenReturn(mock(pe.edu.upeu.eduspace.pago.dto.CreditoDtos.Resultado.class));

        servicio.confirmarOrden(aprobado("150.00"));

        assertThat(o.getEstado()).isEqualTo(OrdenPago.APROBADA);
        verify(credito).cobrarMatricula(7L, 9L, null);
    }
}
