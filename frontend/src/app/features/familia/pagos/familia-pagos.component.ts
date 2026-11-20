import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { EstadoCuenta, EstadoCuota, Pago } from '../../../core/models/cobro.model';
import { CobroService } from '../../../core/services/cobro.service';
import { FamiliaContextoService } from '../../../core/services/familia-contexto.service';
import { NotificationService } from '../../../core/services/notification.service';
import { mensajeError } from '../../../core/utils/errores';
import { ModalComponent } from '../../../shared/components/modal/modal.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { SelectorHijoComponent } from '../../../shared/components/selector-hijo/selector-hijo.component';
import { uuid } from '../../../core/utils/uuid';
import { MatriculaPagoComponent } from './matricula-pago.component';
import { DatosCobroMp, MercadoPagoCheckoutComponent, ResultadoMp } from '../../../shared/components/mercadopago-checkout/mercadopago-checkout.component';
import { Observable } from 'rxjs';

const CLASE: Record<EstadoCuota, string> = { PENDIENTE: 'badge-warn', PARCIAL: 'badge-violet', PAGADA: 'badge-ok', VENCIDA: 'badge-danger', ANULADA: 'badge-gray' };
const PAGABLES: EstadoCuota[] = ['PENDIENTE', 'VENCIDA', 'PARCIAL'];

@Component({
  selector: 'app-familia-pagos',
  imports: [DatePipe, DecimalPipe, ModalComponent, PageHeaderComponent, SelectorHijoComponent, MatriculaPagoComponent, MercadoPagoCheckoutComponent],
  templateUrl: './familia-pagos.component.html'
})
export class FamiliaPagosComponent implements OnInit {
  protected readonly ctx = inject(FamiliaContextoService);
  private readonly api = inject(CobroService);
  private readonly avisos = inject(NotificationService);
  private readonly ruta = inject(ActivatedRoute);

  /** Mercado Pago está activo: «Pagar» manda a su pantalla. */
  protected readonly pasarelaReal = signal(false);
  /** Vuelta de Mercado Pago (?pago=PG-…): el aviso de la pasarela confirma el pago; aquí solo se avisa y se refresca. */
  protected readonly volviste = signal(false);

  protected readonly cuenta = signal<EstadoCuenta | null>(null);
  protected readonly cargando = signal(true);
  protected readonly elegidas = signal<Set<number>>(new Set());

  /** Lo que el apoderado pagó y caja aún no aprueba, o rechazó (con motivo). */
  private readonly enviados = signal<Pago[]>([]);
  private readonly alumnoId = computed(() => this.ctx.seleccionado()?.alumnoId);
  /** Cuotas del hijo elegido con un pago en revisión: no se pueden volver a pagar hasta que caja responda. */
  private readonly cuotasEnRevision = computed(() => new Set(
    this.enviados().filter(p => p.estado === 'PENDIENTE' && p.alumnoId === this.alumnoId()).flatMap(p => p.detalles.map(d => d.cuotaId))
  ));
  protected readonly enRevision = computed(() => this.enviados().filter(p => p.estado === 'PENDIENTE' && p.alumnoId === this.alumnoId()));
  /** Rechazos recientes (14 días) del hijo elegido, para que el apoderado vea el motivo y vuelva a pagar. */
  protected readonly rechazados = computed(() => {
    const limite = Date.now() - 14 * 86_400_000;
    return this.enviados().filter(p => p.estado === 'RECHAZADO' && p.alumnoId === this.alumnoId() && new Date(p.aprobadoEn ?? p.pagadoEn).getTime() >= limite);
  });
  /** La cuota de matrícula no se elige aquí: se paga arriba, con su orden (el servidor también lo exige). */
  protected readonly pagables = computed(() => (this.cuenta()?.cuotas ?? []).filter(c => PAGABLES.includes(c.estado) && !this.cuotasEnRevision().has(c.id) && c.conceptoTipo !== 'MATRICULA'));
  protected readonly totalElegido = computed(() => this.pagables().filter(c => this.elegidas().has(c.id)).reduce((s, c) => s + c.saldo, 0));

  protected readonly confirmando = signal(false);
  protected readonly pagando = signal(false);
  protected readonly errorPago = signal<string | null>(null);
  protected readonly recibo = signal<Pago | null>(null);
  /** Clave de idempotencia del intento: si se pulsa "Pagar" dos veces, el servidor cobra una sola vez. */
  private claveIntento = '';

  constructor() {
    effect(() => {
      const h = this.ctx.seleccionado();
      untracked(() => this.cargar(h?.alumnoId));
    });
  }

  ngOnInit(): void {
    const retorno = this.ruta.snapshot.queryParamMap.get('pago');   // «PG-<id>» al volver de Mercado Pago
    this.api.estadoPasarela().subscribe({
      next: p => {
        this.pasarelaReal.set(p.pasarela === 'MERCADOPAGO');
        this.publicKey.set(p.publicKey ?? '');
        if (retorno && p.pasarela === 'MERCADOPAGO') this.consultarRetorno(retorno);
      },
      error: () => this.pasarelaReal.set(false)
    });
    this.ctx.cargar().subscribe({ error: e => this.avisos.error(mensajeError(e)) });
    this.cargarEnviados();
  }

  private cargarEnviados(): void {
    this.api.misPendientes().subscribe({ next: l => this.enviados.set(l), error: () => this.enviados.set([]) });
  }

  protected clase(e: EstadoCuota): string {
    return CLASE[e];
  }

  protected pagable(c: { id: number; estado: EstadoCuota }): boolean {
    return PAGABLES.includes(c.estado) && !this.cuotasEnRevision().has(c.id) && !this.esMatricula(c.id);
  }

  private esMatricula(cuotaId: number): boolean {
    return this.cuenta()?.cuotas.find(c => c.id === cuotaId)?.conceptoTipo === 'MATRICULA';
  }

  /** ¿La cuota tiene un pago esperando la aprobación de caja? */
  protected enCaja(id: number): boolean {
    return this.cuotasEnRevision().has(id);
  }

  private cargar(alumnoId?: number): void {
    this.elegidas.set(new Set());
    this.cuenta.set(null);
    if (!alumnoId) {
      this.cargando.set(false);
      return;
    }
    this.cargando.set(true);
    this.api.estadoCuenta(alumnoId).subscribe({
      next: c => {
        this.cuenta.set(c);
        this.cargando.set(false);
      },
      error: e => {
        this.cargando.set(false);
        this.avisos.error(mensajeError(e, 'No se pudo cargar la cuenta.'));
      }
    });
  }

  protected alternar(id: number, marcada: boolean): void {
    this.elegidas.update(s => {
      const n = new Set(s);
      if (marcada) n.add(id);
      else n.delete(id);
      return n;
    });
  }

  protected elegirVencidas(): void {
    this.elegidas.set(new Set(this.pagables().filter(c => c.estado === 'VENCIDA' || c.diasAtraso > 0).map(c => c.id)));
  }

  // --- pagar ---
  protected abrirPago(): void {
    if (this.elegidas().size === 0) return;
    this.claveIntento = uuid();
    this.errorPago.set(null);
    this.recibo.set(null);
    this.confirmando.set(true);
  }

  /** Una sola consulta a Mercado Pago al volver; después se refrescan la cuenta y los pagos en revisión. */
  private consultarRetorno(ref: string): void {
    const id = Number(ref.replace(/^PG-/, ''));
    if (!Number.isInteger(id) || id <= 0) return;
    this.volviste.set(true);
    this.api.sincronizarPagoMercadoPago(id).subscribe({
      next: () => { this.cargar(this.ctx.seleccionado()?.alumnoId); this.cargarEnviados(); },
      error: () => this.cargarEnviados()
    });
  }

  protected cerrar(): void {
    this.confirmando.set(false);
    if (this.recibo()) {
      this.cargar(this.ctx.seleccionado()?.alumnoId);
      this.cargarEnviados();
    }
  }

  /** Clave pública de Mercado Pago: con ella se paga aquí mismo (Yape o tarjeta). */
  protected readonly publicKey = signal('');

  /** Cobra las cuotas elegidas en nuestra pantalla. Cada intento lleva su propia clave: un rechazo libera las cuotas para reintentar. */
  protected readonly cobrarMp = (d: DatosCobroMp): Observable<ResultadoMp> => {
    const h = this.ctx.seleccionado()!;
    const detalles = this.pagables().filter(c => this.elegidas().has(c.id)).map(c => ({ cuotaId: c.id, montoAplicado: c.saldo }));
    const pago = { alumnoId: h.alumnoId, montoTotal: detalles.reduce((s, x) => s + x.montoAplicado, 0), medio: 'MERCADOPAGO' as const, idempotencyKey: uuid(), detalles };
    return this.api.pagarPensionesMercadoPagoDirecto(pago, d);
  };

  /** Pago aprobado: se cierra el diálogo y se recargan la cuenta y los pagos. */
  protected pagoMpTerminado(): void {
    this.avisos.success('Pago recibido con Mercado Pago. Te llegará el comprobante.');
    setTimeout(() => {
      this.confirmando.set(false);
      this.elegidas.set(new Set());
      this.cargar(this.ctx.seleccionado()?.alumnoId);
      this.cargarEnviados();
    }, 1800);
  }

  protected pagar(): void {
    const h = this.ctx.seleccionado();
    if (!h) return;
    const detalles = this.pagables().filter(c => this.elegidas().has(c.id)).map(c => ({ cuotaId: c.id, montoAplicado: c.saldo }));
    const cuerpo = { alumnoId: h.alumnoId, montoTotal: detalles.reduce((s, d) => s + d.montoAplicado, 0), medio: 'ONLINE_SIMULADO' as const, idempotencyKey: this.claveIntento, detalles };
    this.pagando.set(true);
    this.errorPago.set(null);
    if (this.pasarelaReal()) {
      // Mercado Pago: el apoderado paga en su pantalla. La confirmación llega por el aviso verificado del servidor.
      this.api.pagarOnlineMercadoPago({ ...cuerpo, medio: 'MERCADOPAGO' }).subscribe({
        next: r => { window.location.assign(r.redireccion); },
        error: e => { this.pagando.set(false); this.errorPago.set(mensajeError(e, 'No se pudo iniciar el pago con Mercado Pago.')); }
      });
      return;
    }
    this.api
      .pagarOnline(cuerpo)
      .subscribe({
        next: p => {
          this.pagando.set(false);
          this.recibo.set(p);
          this.avisos.success('Pago enviado. Caja lo revisa y te llegará el comprobante al aprobarlo.');
        },
        error: e => {
          this.pagando.set(false);
          this.errorPago.set(mensajeError(e, 'No se pudo procesar el pago.'));
        }
      });
  }
}
