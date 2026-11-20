//! Android patch: AArch64 NEON dot-product kernels (`sdot`, FEAT_DotProd).
//!
//! Every kernel reduces each block to the same exact integer sum the scalar
//! definitions in [`super`] compute, then folds it into the result with the
//! same f32 operations in the same order, so results are bit-identical to
//! the scalar path. `kdot::tests::neon_matches_scalar` checks that.
//!
//! Set `KINETIC_NO_NEON=1` to fall back to the scalar kernels, which is how
//! the speed-up of this file is measured.

use std::arch::aarch64::*;
use std::sync::OnceLock;

use super::{half, mins_term, scales_mins, DotFn};
use crate::q8::Q8;
use crate::q8k::Q8K;
use kinetic_quant::TensorType;

pub fn available() -> bool {
    static OK: OnceLock<bool> = OnceLock::new();
    *OK.get_or_init(|| {
        std::env::var_os("KINETIC_NO_NEON").is_none()
            && std::arch::is_aarch64_feature_detected!("neon")
            && std::arch::is_aarch64_feature_detected!("dotprod")
    })
}

pub fn kernel(ty: TensorType) -> Option<DotFn<Q8K>> {
    // SAFETY (each closure): only reachable after `available()` confirmed
    // that the CPU has dotprod, which the implementations are compiled for.
    match ty {
        TensorType::Q4K => Some(|row, x| unsafe { q4_k_impl(row, x) }),
        TensorType::Q6K => Some(|row, x| unsafe { q6_k_impl(row, x) }),
        _ => None,
    }
}

pub fn kernel32(ty: TensorType) -> Option<DotFn<Q8>> {
    match ty {
        TensorType::Q8_0 => Some(|row, x| unsafe { q8_0_impl(row, x) }),
        TensorType::Q4_0 => Some(|row, x| unsafe { q4_0_impl(row, x) }),
        _ => None,
    }
}

/// Kernels for 32-value formats the scalar `legacy` module also defines.
pub fn kernel32_legacy(ty: TensorType) -> Option<DotFn<Q8>> {
    match ty {
        TensorType::Q5_0 => Some(|row, x| unsafe { q5_0_impl(row, x) }),
        _ => None,
    }
}

/// `acc + dot4(a, b)` per 32-bit lane: the `sdot` instruction. The
/// `vdotq_s32` intrinsic is still unstable in Rust, so this is inline
/// assembly with the same meaning.
#[inline]
#[target_feature(enable = "neon,dotprod")]
unsafe fn sdot(acc: int32x4_t, a: int8x16_t, b: int8x16_t) -> int32x4_t {
    let mut r = acc;
    std::arch::asm!(
        "sdot {r:v}.4s, {a:v}.16b, {b:v}.16b",
        r = inout(vreg) r,
        a = in(vreg) a,
        b = in(vreg) b,
        options(pure, nomem, nostack),
    );
    r
}

#[inline(always)]
unsafe fn ld(p: *const u8) -> int8x16_t {
    vld1q_s8(p.cast())
}

#[target_feature(enable = "neon,dotprod")]
unsafe fn q8_0_impl(row: &[u8], x: &Q8) -> f32 {
    assert!(x.qs.len() >= 32 * (row.len() / 34));
    let mut sum = 0.0;
    for (k, blk) in row.chunks_exact(34).enumerate() {
        let y = x.qs.as_ptr().add(32 * k).cast::<u8>();
        let w = blk.as_ptr().add(2);
        let acc = sdot(sdot(vdupq_n_s32(0), ld(w), ld(y)), ld(w.add(16)), ld(y.add(16)));
        sum += x.d[k] * half(blk) * vaddvq_s32(acc) as f32;
    }
    sum
}

#[target_feature(enable = "neon,dotprod")]
unsafe fn q4_0_impl(row: &[u8], x: &Q8) -> f32 {
    assert!(x.qs.len() >= 32 * (row.len() / 18));
    let mut sum = 0.0;
    let m = vdupq_n_u8(0x0F);
    for (k, blk) in row.chunks_exact(18).enumerate() {
        let y = x.qs.as_ptr().add(32 * k).cast::<u8>();
        let q = vld1q_u8(blk.as_ptr().add(2));
        let lo = vreinterpretq_s8_u8(vandq_u8(q, m));
        let hi = vreinterpretq_s8_u8(vshrq_n_u8::<4>(q));
        let acc = sdot(sdot(vdupq_n_s32(0), lo, ld(y)), hi, ld(y.add(16)));
        let sumi = vaddvq_s32(acc);
        let offset = 8 * x.sums[k] as i32;
        sum += x.d[k] * half(blk) * (sumi - offset) as f32;
    }
    sum
}

#[target_feature(enable = "neon,dotprod")]
unsafe fn q5_0_impl(row: &[u8], x: &Q8) -> f32 {
    assert!(x.qs.len() >= 32 * (row.len() / 22));
    const BITS: [u8; 16] = [1, 2, 4, 8, 16, 32, 64, 128, 1, 2, 4, 8, 16, 32, 64, 128];
    let bits = vld1q_u8(BITS.as_ptr());
    let m = vdupq_n_u8(0x0F);
    let sixteen = vdupq_n_u8(0x10);
    let mut sum = 0.0;
    for (k, blk) in row.chunks_exact(22).enumerate() {
        let y = x.qs.as_ptr().add(32 * k).cast::<u8>();
        let qh = u32::from_le_bytes([blk[2], blk[3], blk[4], blk[5]]);
        let q = vld1q_u8(blk.as_ptr().add(6));
        // Element j takes bit j of qh, element j + 16 takes bit j + 16.
        let spread = |b0: u8, b1: u8| {
            vandq_u8(vtstq_u8(vcombine_u8(vdup_n_u8(b0), vdup_n_u8(b1)), bits), sixteen)
        };
        let h_lo = spread(qh as u8, (qh >> 8) as u8);
        let h_hi = spread((qh >> 16) as u8, (qh >> 24) as u8);
        let s16 = vdupq_n_s8(16);
        let w_lo = vsubq_s8(vreinterpretq_s8_u8(vorrq_u8(vandq_u8(q, m), h_lo)), s16);
        let w_hi = vsubq_s8(vreinterpretq_s8_u8(vorrq_u8(vshrq_n_u8::<4>(q), h_hi)), s16);
        let acc = sdot(sdot(vdupq_n_s32(0), w_lo, ld(y)), w_hi, ld(y.add(16)));
        let sumi = vaddvq_s32(acc);
        // Same expression as the scalar `rows` helper, with m = 0.
        sum += x.d[k] * (half(blk) * sumi as f32 + 0.0 * x.sums[k] as f32);
    }
    sum
}

#[target_feature(enable = "neon,dotprod")]
unsafe fn q4_k_impl(row: &[u8], x: &Q8K) -> f32 {
    assert!(x.qs.len() >= 256 * (row.len() / 144));
    let m = vdupq_n_u8(0x0F);
    let mut sum = 0.0;
    for (b, blk) in row.chunks_exact(144).enumerate() {
        let (dx, q8, bsums) = x.block(b);
        let (sc, mins) = scales_mins(&blk[4..16]);
        let qs = blk.as_ptr().add(16);
        let y = q8.as_ptr().cast::<u8>();
        let mut acc = vdupq_n_s32(0);
        for j in 0..4 {
            let q0 = vld1q_u8(qs.add(32 * j));
            let q1 = vld1q_u8(qs.add(32 * j + 16));
            let lo = sdot(
                sdot(vdupq_n_s32(0), vreinterpretq_s8_u8(vandq_u8(q0, m)), ld(y.add(64 * j))),
                vreinterpretq_s8_u8(vandq_u8(q1, m)),
                ld(y.add(64 * j + 16)),
            );
            let hi = sdot(
                sdot(
                    vdupq_n_s32(0),
                    vreinterpretq_s8_u8(vshrq_n_u8::<4>(q0)),
                    ld(y.add(64 * j + 32)),
                ),
                vreinterpretq_s8_u8(vshrq_n_u8::<4>(q1)),
                ld(y.add(64 * j + 48)),
            );
            acc = vmlaq_n_s32(acc, lo, sc[2 * j] as i32);
            acc = vmlaq_n_s32(acc, hi, sc[2 * j + 1] as i32);
        }
        let sumi = vaddvq_s32(acc);
        sum += dx
            * (half(&blk[0..2]) * sumi as f32 - half(&blk[2..4]) * mins_term(&mins, bsums) as f32);
    }
    sum
}

#[target_feature(enable = "neon,dotprod")]
unsafe fn q6_k_impl(row: &[u8], x: &Q8K) -> f32 {
    assert!(x.qs.len() >= 256 * (row.len() / 210));
    let m4 = vdupq_n_u8(0x0F);
    let m3 = vdupq_n_u8(0x03);
    let s32 = vdupq_n_s8(32);
    let mut sum = 0.0;
    for (b, blk) in row.chunks_exact(210).enumerate() {
        let (dx, q8, _) = x.block(b);
        let (ql, qh, sc) = (blk.as_ptr(), blk.as_ptr().add(128), &blk[192..208]);
        let y = q8.as_ptr().cast::<u8>();
        let mut acc = vdupq_n_s32(0);
        for n in 0..2 {
            let ql = ql.add(64 * n);
            let qh = qh.add(32 * n);
            for h in 0..2 {
                // Lanes l = 16h .. 16h + 16 of the 32-value rows.
                let a = vld1q_u8(ql.add(16 * h));
                let c = vld1q_u8(ql.add(32 + 16 * h));
                let hb = vld1q_u8(qh.add(16 * h));
                let q = [
                    vorrq_u8(vandq_u8(a, m4), vshlq_n_u8::<4>(vandq_u8(hb, m3))),
                    vorrq_u8(vandq_u8(c, m4), vshlq_n_u8::<4>(vandq_u8(vshrq_n_u8::<2>(hb), m3))),
                    vorrq_u8(vshrq_n_u8::<4>(a), vshlq_n_u8::<4>(vandq_u8(vshrq_n_u8::<4>(hb), m3))),
                    vorrq_u8(vshrq_n_u8::<4>(c), vshlq_n_u8::<4>(vshrq_n_u8::<6>(hb))),
                ];
                for (g, &qg) in q.iter().enumerate() {
                    let w = vsubq_s8(vreinterpretq_s8_u8(qg), s32);
                    let e = 128 * n + 32 * g + 16 * h;
                    let d = sdot(vdupq_n_s32(0), w, ld(y.add(e)));
                    acc = vmlaq_n_s32(acc, d, sc[e / 16] as i8 as i32);
                }
            }
        }
        sum += dx * half(&blk[208..210]) * vaddvq_s32(acc) as f32;
    }
    sum
}

#[cfg(test)]
mod tests {
    use super::super::tests::{extreme_row, random_row, Lcg};
    use super::*;
    use crate::{q8::Q8, q8k::Q8K};

    fn x_vec(rng: &mut Lcg, n: usize) -> Vec<f32> {
        (0..n).map(|_| rng.f32() * 3.0).collect()
    }

    #[test]
    fn neon_matches_scalar() {
        if !available() {
            eprintln!("skipped: this CPU has no dotprod");
            return;
        }
        let mut rng = Lcg(7);
        for blocks in [1usize, 2, 5, 16] {
            for ty in [TensorType::Q4K, TensorType::Q6K] {
                let n = blocks * 256;
                let x = Q8K::quantize(&x_vec(&mut rng, n));
                let scalar = super::super::scalar_kernel(ty).unwrap();
                let neon = kernel(ty).unwrap();
                for row in [random_row(ty, blocks, &mut rng), extreme_row(ty, blocks, &mut rng)] {
                    assert_eq!(scalar(&row, &x).to_bits(), neon(&row, &x).to_bits(), "{ty:?} x{blocks}");
                }
            }
            for ty in [TensorType::Q8_0, TensorType::Q4_0, TensorType::Q5_0] {
                let n = blocks * 32;
                let x = Q8::quantize(&x_vec(&mut rng, n));
                let scalar = super::super::scalar_kernel32(ty).unwrap();
                let neon = kernel32(ty).or_else(|| kernel32_legacy(ty)).unwrap();
                for row in [random_row(ty, blocks, &mut rng), extreme_row(ty, blocks, &mut rng)] {
                    assert_eq!(scalar(&row, &x).to_bits(), neon(&row, &x).to_bits(), "{ty:?} x{blocks}");
                }
            }
        }
    }
}
