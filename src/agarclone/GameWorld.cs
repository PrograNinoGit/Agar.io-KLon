using System;
using System.Collections.Generic;
using Microsoft.Xna.Framework;

namespace Agar3D.Model
{
    /// <summary>
    /// Die komplette Spielwelt in 3D. Alle Objekte haben X/Y/Z (Kugelmittelpunkt),
    /// Kollisionen laufen ueber den echten 3D-Abstand und Y bleibt zwischen 0 und WorldHeight.
    /// Update() wird einmal pro Spiel-Tick aufgerufen.
    /// </summary>
    public sealed class GameWorld
    {
        public const float WorldWidth = 4000f;
        public const float WorldDepth = 4000f;
        public const float WorldHeight = 1200f;

        private const int FoodTarget = 2500;
        private const int BotTarget = 24;
        private const int VirusTarget = 14;
        private const int MaxCellsPerOwner = 16;

        private const double StartRadius = 20;
        private const double MaxRadius = 300;
        private const double MinSplitRadius = 18;
        private const double MinEjectRadius = 24;
        private const double EjectMassLoss = 20;
        private const double EjectRadius = 5;
        private const double SplitBoost = 22;
        private const double EjectSpeed = 16;
        private const int MergeDelayTicks = 420;
        private const double VirusRadius = 38;

        // Selbststaendiges Zusammenfuegen (nach Ablauf von MergeDelayTicks)
        private const double CohesionSpeed = 0.8;   // staendige schwache Anziehung, auch waehrend des Cooldowns (Einheiten pro Tick)
        private const double MergePullSpeed = 3.0;  // staerkere Anziehung merge-bereiter Zellen (Einheiten pro Tick)
        private const double MergeReach = 0.5;      // Merge sobald d < big.Radius + small.Radius * MergeReach

        private static readonly string[] BotNames =
        {
            "Alpha", "Blob", "Nova", "Pixel", "Turbo", "Zed", "Luna", "Rex", "Mika", "Orbit",
            "Gizmo", "Pogo", "Echo", "Dash", "Fuzzy", "Bolt", "Kiwi", "Sky", "Tango", "Yoshi",
            "Milo", "Ziggy", "Comet", "Nugget"
        };

        private readonly Random _rng = new Random();
        private int _tick;

        public readonly List<Food> Foods = new List<Food>();
        public readonly List<EjectedMass> EjectedMasses = new List<EjectedMass>();
        public readonly List<Virus> Viruses = new List<Virus>();
        public readonly List<Bot> Bots = new List<Bot>();
        public readonly List<Cell> PlayerCells = new List<Cell>();

        public readonly Difficulty Difficulty;
        public readonly string PlayerName;
        public readonly Color PlayerColor;

        // Zielrichtung (normalisiert) aus Kamera/Maus + Staerke 0..1
        public double TargetX { get; set; }
        public double TargetY { get; set; }
        public double TargetZ { get; set; }
        private double _aimX, _aimY, _aimZ = 1;
        private double _aimStrength = 1;

        // Kamerafokus und Spielergroesse
        public double FocusX { get; private set; }
        public double FocusY { get; private set; }
        public double FocusZ { get; private set; }
        public double PlayerTotalMass { get; private set; }
        public double PlayerTotalRadius { get; private set; }

        public bool IsGameOver { get { return PlayerCells.Count == 0; } }
        public bool GameOver { get { return PlayerCells.Count == 0; } }

        public string DifficultyLabel
        {
            get
            {
                switch (Difficulty)
                {
                    case Difficulty.Easy: return "Leicht";
                    case Difficulty.Hard: return "Schwer";
                    default: return "Extrem";
                }
            }
        }
        public double Score { get { return PlayerTotalMass; } }

        public GameWorld(Difficulty difficulty)
            : this(difficulty, "Du", new Color(66, 135, 245))
        {
        }

        public GameWorld(Difficulty difficulty, string playerName)
            : this(difficulty, playerName, new Color(66, 135, 245))
        {
        }

        public GameWorld(Difficulty difficulty, string playerName, Color playerColor)
        {
            Difficulty = difficulty;
            PlayerName = string.IsNullOrWhiteSpace(playerName) ? "Du" : playerName;
            PlayerColor = playerColor;

            for (int i = 0; i < FoodTarget; i++) Foods.Add(CreateFood());
            for (int i = 0; i < VirusTarget; i++) Viruses.Add(CreateVirus());
            for (int i = 0; i < BotTarget; i++) Bots.Add(CreateBot());
            SpawnPlayer();
            UpdateFocus();
            TargetX = FocusX; TargetY = FocusY; TargetZ = FocusZ;
        }

        // ------------------------------------------------------------------
        // Oeffentliche Steuerung
        // ------------------------------------------------------------------

        /// <summary>Richtung (z. B. aus Renderer3D.GetAimDirection) und Staerke 0..1.</summary>
        public void SetAimDirection(Vector3 direction, double strength = 1.0)
        {
            double x = direction.X, y = direction.Y, z = direction.Z;
            if (!Normalize(ref x, ref y, ref z)) return;
            _aimX = x; _aimY = y; _aimZ = z;
            _aimStrength = Math.Max(0.0, Math.Min(1.0, strength));
            TargetX = FocusX + x * 500;
            TargetY = FocusY + y * 500;
            TargetZ = FocusZ + z * 500;
        }

        /// <summary>Zielpunkt im Raum: die Zellen bewegen sich vom Fokus in diese Richtung.</summary>
        public void SetAimPoint(double x, double y, double z)
        {
            SetAimDirection(new Vector3((float)(x - FocusX), (float)(y - FocusY), (float)(z - FocusZ)), 1.0);
        }

        public void Respawn()
        {
            PlayerCells.Clear();
            SpawnPlayer();
            UpdateFocus();
        }

        public IEnumerable<Cell> AllEnemyCells
        {
            get
            {
                foreach (Bot b in Bots)
                    foreach (Cell c in b.Cells)
                        yield return c;
            }
        }

        /// <summary>Leitet die Bewegungsrichtung aus TargetX/Y/Z (3D) ab.</summary>
        private void RefreshAim()
        {
            double dx = TargetX - FocusX, dy = TargetY - FocusY, dz = TargetZ - FocusZ;
            double dist = Math.Sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 1e-6) { _aimStrength = 0; return; }
            _aimX = dx / dist; _aimY = dy / dist; _aimZ = dz / dist;
            _aimStrength = Math.Min(1.0, dist / 40.0);
        }

        public void Update(double dt)
        {
            Update();
        }

        public bool TrySplit()
        {
            RefreshAim();
            bool any = false;
            foreach (Cell c in PlayerCells.ToArray())
            {
                if (SplitCell(c, PlayerCells, _aimX, _aimY, _aimZ)) any = true;
            }
            return any;
        }

        public bool TryEject()
        {
            RefreshAim();
            bool any = false;
            foreach (Cell c in PlayerCells)
            {
                if (c.Radius < MinEjectRadius) continue;
                c.Radius = Math.Sqrt(Math.Max(1.0, c.Mass - EjectMassLoss));

                double sx = c.X + _aimX * (c.Radius + EjectRadius + 2);
                double sy = c.Y + _aimY * (c.Radius + EjectRadius + 2);
                double sz = c.Z + _aimZ * (c.Radius + EjectRadius + 2);
                ClampPoint(ref sx, ref sy, ref sz, EjectRadius);

                EjectedMasses.Add(new EjectedMass(sx, sy, sz, EjectRadius, c.Color,
                    _aimX * EjectSpeed, _aimY * EjectSpeed, _aimZ * EjectSpeed, 12));
                any = true;
            }
            return any;
        }

        // ------------------------------------------------------------------
        // Spiel-Tick
        // ------------------------------------------------------------------

        public void Update()
        {
            _tick++;
            RefreshAim();
            TickTimers();

            foreach (Cell c in PlayerCells)
            {
                c.Dx = _aimX; c.Dy = _aimY; c.Dz = _aimZ;
                MoveCell(c, _aimX, _aimY, _aimZ, _aimStrength);
            }
            foreach (Bot bot in Bots) UpdateBot(bot);

            SeparateOrMerge(PlayerCells);
            foreach (Bot bot in Bots) SeparateOrMerge(bot.Cells);

            UpdateEjected();
            UpdateViruses();

            CollectPickups(PlayerCells);
            foreach (Bot bot in Bots) CollectPickups(bot.Cells);

            FeedViruses();

            CollideViruses(PlayerCells);
            foreach (Bot bot in Bots) CollideViruses(bot.Cells);

            foreach (Bot bot in Bots) ResolveEating(PlayerCells, bot.Cells);
            for (int a = 0; a < Bots.Count; a++)
                for (int b = a + 1; b < Bots.Count; b++)
                    ResolveEating(Bots[a].Cells, Bots[b].Cells);
            Bots.RemoveAll(b => b.Cells.Count == 0);

            DecayCells(PlayerCells);
            foreach (Bot bot in Bots) DecayCells(bot.Cells);

            Respawn_Entities();
            UpdateFocus();
        }

        private void TickTimers()
        {
            foreach (Cell c in PlayerCells) TickCell(c);
            foreach (Bot b in Bots) foreach (Cell c in b.Cells) TickCell(c);
            foreach (EjectedMass em in EjectedMasses)
                if (em.OwnerImmuneTicks > 0) em.OwnerImmuneTicks--;
        }

        private static void TickCell(Cell c)
        {
            if (c.MergeCooldown > 0) c.MergeCooldown--;
            if (c.AiActionCooldown > 0) c.AiActionCooldown--;
        }

        // ------------------------------------------------------------------
        // Bewegung
        // ------------------------------------------------------------------

        private void MoveCell(Cell c, double dx, double dy, double dz, double strength)
        {
            double sp = c.Speed * strength;
            c.X += dx * sp + c.BoostX;
            c.Y += dy * sp + c.BoostY;
            c.Z += dz * sp + c.BoostZ;

            c.BoostX *= 0.90; c.BoostY *= 0.90; c.BoostZ *= 0.90;
            if (Math.Abs(c.BoostX) < 0.02) c.BoostX = 0;
            if (Math.Abs(c.BoostY) < 0.02) c.BoostY = 0;
            if (Math.Abs(c.BoostZ) < 0.02) c.BoostZ = 0;

            ClampCell(c);
        }

        private void UpdateEjected()
        {
            foreach (EjectedMass em in EjectedMasses)
            {
                em.X += em.Vx; em.Y += em.Vy; em.Z += em.Vz;
                em.Vx *= 0.93; em.Vy *= 0.93; em.Vz *= 0.93;
                if (Math.Abs(em.Vx) < 0.05) em.Vx = 0;
                if (Math.Abs(em.Vy) < 0.05) em.Vy = 0;
                if (Math.Abs(em.Vz) < 0.05) em.Vz = 0;
                double x = em.X, y = em.Y, z = em.Z;
                ClampPoint(ref x, ref y, ref z, em.Radius);
                em.X = x; em.Y = y; em.Z = z;
            }
        }

        private void UpdateViruses()
        {
            foreach (Virus v in Viruses)
            {
                v.Spin += 0.01f;
                v.X += v.Vx; v.Y += v.Vy; v.Z += v.Vz;
                v.Vx *= 0.90; v.Vy *= 0.90; v.Vz *= 0.90;
                if (Math.Abs(v.Vx) < 0.05) v.Vx = 0;
                if (Math.Abs(v.Vy) < 0.05) v.Vy = 0;
                if (Math.Abs(v.Vz) < 0.05) v.Vz = 0;
                double x = v.X, y = v.Y, z = v.Z;
                ClampPoint(ref x, ref y, ref z, v.Radius);
                v.X = x; v.Y = y; v.Z = z;
            }
        }

        // ------------------------------------------------------------------
        // Bots
        // ------------------------------------------------------------------

        private void UpdateBot(Bot bot)
        {
            double cx = bot.AvgX, cy = bot.AvgY, cz = bot.AvgZ;

            Cell biggest = bot.Cells[0];
            foreach (Cell c in bot.Cells) if (c.Radius > biggest.Radius) biggest = c;

            double dx = 0, dy = 0, dz = 0;
            bool steer = false;
            Cell prey = null;

            if (Difficulty != Difficulty.Easy && PlayerCells.Count > 0)
            {
                Cell nearest = null;
                double nd = double.MaxValue;
                foreach (Cell p in PlayerCells)
                {
                    double d = Dist(cx, cy, cz, p.X, p.Y, p.Z);
                    if (d < nd) { nd = d; nearest = p; }
                }
                if (nearest != null && nd < 1000)
                {
                    double tx = nearest.X - cx, ty = nearest.Y - cy, tz = nearest.Z - cz;
                    if (nearest.Radius > biggest.Radius * 1.1)
                    {
                        dx = -tx; dy = -ty; dz = -tz;       // fliehen
                        steer = Normalize(ref dx, ref dy, ref dz);
                    }
                    else if (biggest.Radius > nearest.Radius * 1.1)
                    {
                        dx = tx; dy = ty; dz = tz;          // jagen
                        steer = Normalize(ref dx, ref dy, ref dz);
                        if (steer) prey = nearest;
                    }
                }
            }

            if (!steer)
            {
                bot.WanderTimer--;
                if (bot.WanderTimer <= 0 || (bot.WanderDx == 0 && bot.WanderDy == 0 && bot.WanderDz == 0))
                {
                    double rx, ry, rz;
                    RandomDirection(out rx, out ry, out rz);
                    bot.WanderDx = rx; bot.WanderDy = ry * 0.5; bot.WanderDz = rz;
                    bot.WanderTimer = 60 + _rng.Next(120);
                }
                // An den Raendern umkehren
                const double margin = 200;
                if ((cx < margin && bot.WanderDx < 0) || (cx > WorldWidth - margin && bot.WanderDx > 0)) bot.WanderDx = -bot.WanderDx;
                if ((cy < margin && bot.WanderDy < 0) || (cy > WorldHeight - margin && bot.WanderDy > 0)) bot.WanderDy = -bot.WanderDy;
                if ((cz < margin && bot.WanderDz < 0) || (cz > WorldDepth - margin && bot.WanderDz > 0)) bot.WanderDz = -bot.WanderDz;

                dx = bot.WanderDx; dy = bot.WanderDy; dz = bot.WanderDz;
                Normalize(ref dx, ref dy, ref dz);
            }

            foreach (Cell c in bot.Cells)
            {
                c.Dx = dx; c.Dy = dy; c.Dz = dz;
                MoveCell(c, dx, dy, dz, 1.0);
            }

            if (Difficulty == Difficulty.Extreme && prey != null && bot.Cells.Count < 4)
            {
                foreach (Cell c in bot.Cells.ToArray())
                {
                    if (c.AiActionCooldown > 0 || c.Radius < 30) continue;
                    double half = c.Radius / Math.Sqrt(2);
                    double d = Dist(c.X, c.Y, c.Z, prey.X, prey.Y, prey.Z);
                    if (half > prey.Radius * 1.1 && d < c.Radius * 6)
                    {
                        double sx = prey.X - c.X, sy = prey.Y - c.Y, sz = prey.Z - c.Z;
                        if (Normalize(ref sx, ref sy, ref sz) && SplitCell(c, bot.Cells, sx, sy, sz))
                        {
                            c.AiActionCooldown = 240;
                            bot.Cells[bot.Cells.Count - 1].AiActionCooldown = 240;
                        }
                        break;
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Split / Merge / Viren
        // ------------------------------------------------------------------

        private bool SplitCell(Cell c, List<Cell> owner, double dx, double dy, double dz)
        {
            if (owner.Count >= MaxCellsPerOwner || c.Radius < MinSplitRadius) return false;

            double newR = c.Radius / Math.Sqrt(2.0);
            c.Radius = newR;
            c.MergeCooldown = MergeDelayTicks;

            Cell n = new Cell(c.X, c.Y, c.Z, newR, c.Color, c.Name);
            n.BoostX = dx * SplitBoost;
            n.BoostY = dy * SplitBoost;
            n.BoostZ = dz * SplitBoost;
            n.MergeCooldown = MergeDelayTicks;
            owner.Add(n);
            return true;
        }

        /// <summary>
        /// Alle eigenen Zellen naehern sich staendig an: waehrend des Cooldowns schwach zum
        /// Gesamtschwerpunkt (CohesionSpeed), nach dem Cooldown stark zum Schwerpunkt der
        /// merge-bereiten Zellen (MergePullSpeed). Die normale Bewegung bleibt unveraendert,
        /// die Anziehung kommt nur additiv dazu.
        /// </summary>
        private static void PullCellsTogether(List<Cell> cells)
        {
            if (cells.Count < 2) return;

            double mass = 0, cx = 0, cy = 0, cz = 0;        // Schwerpunkt aller Zellen
            double rMass = 0, rx = 0, ry = 0, rz = 0;       // Schwerpunkt der merge-bereiten Zellen
            int ready = 0;
            foreach (Cell c in cells)
            {
                double m = c.Mass;
                mass += m; cx += c.X * m; cy += c.Y * m; cz += c.Z * m;
                if (c.MergeCooldown <= 0)
                {
                    rMass += m; rx += c.X * m; ry += c.Y * m; rz += c.Z * m;
                    ready++;
                }
            }
            cx /= mass; cy /= mass; cz /= mass;
            if (ready >= 2) { rx /= rMass; ry /= rMass; rz /= rMass; }

            foreach (Cell c in cells)
            {
                double tx, ty, tz, speed;
                if (ready >= 2 && c.MergeCooldown <= 0)
                {
                    tx = rx; ty = ry; tz = rz; speed = MergePullSpeed;   // merge-bereit: stark zueinander
                }
                else
                {
                    tx = cx; ty = cy; tz = cz; speed = CohesionSpeed;    // Cooldown: schwach zum Gesamtschwerpunkt
                }

                double dx = tx - c.X, dy = ty - c.Y, dz = tz - c.Z;
                double d = Math.Sqrt(dx * dx + dy * dy + dz * dz);
                if (d < 1e-6) continue;
                double step = Math.Min(d, speed);
                c.X += dx / d * step;
                c.Y += dy / d * step;
                c.Z += dz / d * step;
                ClampCell(c);
            }
        }

        private void SeparateOrMerge(List<Cell> cells)
        {
            PullCellsTogether(cells);

            for (int i = 0; i < cells.Count; i++)
            {
                for (int j = i + 1; j < cells.Count; j++)
                {
                    Cell a = cells[i], b = cells[j];
                    double dx = b.X - a.X, dy = b.Y - a.Y, dz = b.Z - a.Z;
                    double d = Math.Sqrt(dx * dx + dy * dy + dz * dz);

                    bool canMerge = a.MergeCooldown <= 0 && b.MergeCooldown <= 0;
                    if (canMerge)
                    {
                        Cell big = a.Radius >= b.Radius ? a : b;
                        Cell small = big == a ? b : a;
                        if (d < big.Radius + small.Radius * MergeReach)
                        {
                            double m = big.Mass + small.Mass;
                            big.X = (big.X * big.Mass + small.X * small.Mass) / m;
                            big.Y = (big.Y * big.Mass + small.Y * small.Mass) / m;
                            big.Z = (big.Z * big.Mass + small.Z * small.Mass) / m;
                            big.Radius = Math.Min(MaxRadius, Math.Sqrt(m));
                            cells.Remove(small);
                            ClampCell(big);
                            i = -1; // von vorn beginnen
                            break;
                        }
                    }
                    else
                    {
                        double min = a.Radius + b.Radius;
                        if (d < min)
                        {
                            if (d < 1e-6) { RandomDirection(out dx, out dy, out dz); d = 1; }
                            else { dx /= d; dy /= d; dz /= d; }
                            double push = (min - d) * 0.5;
                            a.X -= dx * push; a.Y -= dy * push; a.Z -= dz * push;
                            b.X += dx * push; b.Y += dy * push; b.Z += dz * push;
                            ClampCell(a); ClampCell(b);
                        }
                    }
                }
            }
        }

        private void CollideViruses(List<Cell> cells)
        {
            foreach (Cell c in cells.ToArray())
            {
                for (int vi = Viruses.Count - 1; vi >= 0; vi--)
                {
                    Virus v = Viruses[vi];
                    if (c.Radius <= v.Radius * 1.15) continue;
                    double d = Dist(c.X, c.Y, c.Z, v.X, v.Y, v.Z);
                    if (d < c.Radius - v.Radius * 0.3)
                    {
                        Viruses.RemoveAt(vi);
                        PopCell(c, cells);
                        break;
                    }
                }
            }
        }

        private void PopCell(Cell c, List<Cell> owner)
        {
            int free = MaxCellsPerOwner - owner.Count;
            if (free <= 0)
            {
                Grow(c, 100);
                return;
            }
            int n = Math.Min(8, free + 1);
            double newR = c.Radius / Math.Sqrt(n);
            c.Radius = newR;
            c.MergeCooldown = MergeDelayTicks;

            for (int k = 0; k < n - 1; k++)
            {
                double dx, dy, dz;
                RandomDirection(out dx, out dy, out dz);
                Cell p = new Cell(c.X, c.Y, c.Z, newR, c.Color, c.Name);
                p.BoostX = dx * 18; p.BoostY = dy * 18; p.BoostZ = dz * 18;
                p.MergeCooldown = MergeDelayTicks;
                owner.Add(p);
            }
        }

        private void FeedViruses()
        {
            for (int ei = EjectedMasses.Count - 1; ei >= 0; ei--)
            {
                EjectedMass em = EjectedMasses[ei];
                foreach (Virus v in Viruses)
                {
                    if (Dist(em.X, em.Y, em.Z, v.X, v.Y, v.Z) >= v.Radius) continue;

                    EjectedMasses.RemoveAt(ei);
                    v.FeedCount++;
                    if (v.FeedCount >= 7)
                    {
                        v.FeedCount = 0;
                        double vx = em.Vx, vy = em.Vy, vz = em.Vz;
                        if (!Normalize(ref vx, ref vy, ref vz)) RandomDirection(out vx, out vy, out vz);
                        Virus nv = new Virus(v.X, v.Y, v.Z, v.Radius);
                        nv.Vx = vx * 18; nv.Vy = vy * 18; nv.Vz = vz * 18;
                        Viruses.Add(nv);
                    }
                    break;
                }
            }
        }

        // ------------------------------------------------------------------
        // Fressen
        // ------------------------------------------------------------------

        private void CollectPickups(List<Cell> cells)
        {
            foreach (Cell c in cells)
            {
                double r = c.Radius;
                for (int i = Foods.Count - 1; i >= 0; i--)
                {
                    Food f = Foods[i];
                    double dx = f.X - c.X; if (dx > r || dx < -r) continue;
                    double dy = f.Y - c.Y; if (dy > r || dy < -r) continue;
                    double dz = f.Z - c.Z; if (dz > r || dz < -r) continue;
                    if (Math.Sqrt(dx * dx + dy * dy + dz * dz) < r)
                    {
                        Grow(c, f.Radius * f.Radius * 0.5);
                        Foods[i] = Foods[Foods.Count - 1];
                        Foods.RemoveAt(Foods.Count - 1);
                    }
                }
                for (int i = EjectedMasses.Count - 1; i >= 0; i--)
                {
                    EjectedMass em = EjectedMasses[i];
                    if (em.OwnerImmuneTicks > 0 || c.Radius <= em.Radius * 1.2) continue;
                    if (Dist(c.X, c.Y, c.Z, em.X, em.Y, em.Z) < c.Radius)
                    {
                        Grow(c, em.Radius * em.Radius);
                        EjectedMasses.RemoveAt(i);
                    }
                }
            }
        }

        private static bool CanEat(Cell big, Cell small, double dist)
        {
            return big.Radius > small.Radius * 1.1 && dist < big.Radius - small.Radius * 0.4;
        }

        private void ResolveEating(List<Cell> listA, List<Cell> listB)
        {
            for (int i = listA.Count - 1; i >= 0; i--)
            {
                Cell a = listA[i];
                for (int j = listB.Count - 1; j >= 0; j--)
                {
                    Cell b = listB[j];
                    double d = Dist(a.X, a.Y, a.Z, b.X, b.Y, b.Z);
                    if (CanEat(a, b, d))
                    {
                        Grow(a, b.Mass);
                        listB.RemoveAt(j);
                    }
                    else if (CanEat(b, a, d))
                    {
                        Grow(b, a.Mass);
                        listA.RemoveAt(i);
                        break;
                    }
                }
            }
        }

        private static void Grow(Cell c, double mass)
        {
            c.Radius = Math.Min(MaxRadius, Math.Sqrt(c.Radius * c.Radius + mass));
        }

        private static void DecayCells(List<Cell> cells)
        {
            foreach (Cell c in cells)
                if (c.Radius > 50) c.Radius *= 0.99995;
        }

        // ------------------------------------------------------------------
        // Spawning
        // ------------------------------------------------------------------

        private void Respawn_Entities()
        {
            for (int i = 0; i < 5 && Foods.Count < FoodTarget; i++) Foods.Add(CreateFood());
            if (Bots.Count < BotTarget) Bots.Add(CreateBot());
            if (_tick % 60 == 0 && Viruses.Count < VirusTarget) Viruses.Add(CreateVirus());
        }

        private void SpawnPlayer()
        {
            Cell c = new Cell(
                1000 + _rng.NextDouble() * 2000,
                300 + _rng.NextDouble() * 400,
                1000 + _rng.NextDouble() * 2000,
                StartRadius, PlayerColor, PlayerName);
            ClampCell(c);
            PlayerCells.Add(c);
        }

        private Food CreateFood()
        {
            double r = 3.5 + _rng.NextDouble() * 2.0;
            double x = RandomIn(r, WorldWidth - r);
            double y = RandomIn(r, WorldHeight - r);
            double z = RandomIn(r, WorldDepth - r);
            return new Food(x, y, z, r, RandomColor(), (float)(_rng.NextDouble() * Math.PI * 2));
        }

        private Virus CreateVirus()
        {
            double r = VirusRadius;
            return new Virus(RandomIn(r, WorldWidth - r), RandomIn(r, WorldHeight - r), RandomIn(r, WorldDepth - r), r);
        }

        private Bot CreateBot()
        {
            double r = 14 + _rng.NextDouble() * 16;
            Cell c = new Cell(RandomIn(r, WorldWidth - r), RandomIn(r, WorldHeight - r), RandomIn(r, WorldDepth - r),
                r, RandomColor(), BotNames[_rng.Next(BotNames.Length)]);
            return new Bot(c);
        }

        private double RandomIn(double min, double max)
        {
            return min + _rng.NextDouble() * (max - min);
        }

        private Color RandomColor()
        {
            float h = (float)_rng.NextDouble() * 6f;
            int i = (int)h;
            float f = h - i;
            float q = 1f - f;
            float r, g, b;
            switch (i % 6)
            {
                case 0: r = 1; g = f; b = 0.25f; break;
                case 1: r = q; g = 1; b = 0.25f; break;
                case 2: r = 0.25f; g = 1; b = f; break;
                case 3: r = 0.25f; g = q; b = 1; break;
                case 4: r = f; g = 0.25f; b = 1; break;
                default: r = 1; g = 0.25f; b = q; break;
            }
            return new Color(r * 0.9f, g * 0.9f, b * 0.9f);
        }

        // ------------------------------------------------------------------
        // Fokus, Grenzen, Mathe
        // ------------------------------------------------------------------

        private void UpdateFocus()
        {
            if (PlayerCells.Count == 0) return;
            double mass = 0, x = 0, y = 0, z = 0;
            foreach (Cell c in PlayerCells)
            {
                double m = c.Mass;
                mass += m; x += c.X * m; y += c.Y * m; z += c.Z * m;
            }
            FocusX = x / mass; FocusY = y / mass; FocusZ = z / mass;
            PlayerTotalMass = mass;
            PlayerTotalRadius = Math.Sqrt(mass);
        }

        private static void ClampCell(Cell c)
        {
            double x = c.X, y = c.Y, z = c.Z;
            ClampPoint(ref x, ref y, ref z, c.Radius);
            c.X = x; c.Y = y; c.Z = z;
        }

        /// <summary>Haelt eine Kugel komplett in der Arena: X, Y (0..WorldHeight) und Z.</summary>
        private static void ClampPoint(ref double x, ref double y, ref double z, double r)
        {
            x = Math.Max(r, Math.Min(WorldWidth - r, x));
            y = Math.Max(r, Math.Min(WorldHeight - r, y));
            z = Math.Max(r, Math.Min(WorldDepth - r, z));
        }

        private static double Dist(double ax, double ay, double az, double bx, double by, double bz)
        {
            double dx = ax - bx, dy = ay - by, dz = az - bz;
            return Math.Sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static bool Normalize(ref double x, ref double y, ref double z)
        {
            double len = Math.Sqrt(x * x + y * y + z * z);
            if (len < 1e-9) return false;
            x /= len; y /= len; z /= len;
            return true;
        }

        private void RandomDirection(out double x, out double y, out double z)
        {
            x = _rng.NextDouble() * 2 - 1;
            y = _rng.NextDouble() * 2 - 1;
            z = _rng.NextDouble() * 2 - 1;
            if (!Normalize(ref x, ref y, ref z)) { x = 1; y = 0; z = 0; }
        }
    }
}
