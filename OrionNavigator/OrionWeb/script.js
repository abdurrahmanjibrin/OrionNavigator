/* ============================================
   ORION — Landing Page Script
   Language Toggle / Scroll Animations / Mobile Nav
   ============================================ */

// ---------- Language System ----------
let currentLang = 'en';

function setLanguage(lang) {
    currentLang = lang;

    // Update all elements with data-lang attributes
    document.querySelectorAll('[data-lang-en]').forEach(el => {
        const text = el.getAttribute(`data-lang-${lang}`);
        if (text) {
            el.textContent = text;
        }
    });

    // Update desktop toggle buttons
    document.querySelectorAll('.lang-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.lang === lang);
    });

    // Update mobile toggle buttons
    document.querySelectorAll('.mobile-lang-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.lang === lang);
    });

    // Save preference
    localStorage.setItem('orion-lang', lang);
}

// ---------- Navbar Scroll Effect ----------
const navbar = document.getElementById('navbar');
let lastScrollY = 0;

function handleNavbarScroll() {
    const scrollY = window.scrollY;

    if (scrollY > 50) {
        navbar.classList.add('scrolled');
    } else {
        navbar.classList.remove('scrolled');
    }

    lastScrollY = scrollY;
}

// ---------- Mobile Navigation ----------
const hamburger = document.getElementById('navHamburger');
const mobileNav = document.getElementById('mobileNav');
const mobileOverlay = document.getElementById('mobileOverlay');

function toggleMobileNav() {
    hamburger.classList.toggle('active');
    mobileNav.classList.toggle('active');
    mobileOverlay.classList.toggle('active');
    document.body.style.overflow = mobileNav.classList.contains('active') ? 'hidden' : '';
}

function closeMobileNav() {
    hamburger.classList.remove('active');
    mobileNav.classList.remove('active');
    mobileOverlay.classList.remove('active');
    document.body.style.overflow = '';
}

hamburger.addEventListener('click', toggleMobileNav);
mobileOverlay.addEventListener('click', closeMobileNav);

// Close mobile nav when clicking nav links
mobileNav.querySelectorAll('a').forEach(link => {
    link.addEventListener('click', closeMobileNav);
});

// ---------- Scroll Reveal Animations ----------
function revealOnScroll() {
    const reveals = document.querySelectorAll('.reveal, .reveal-left, .reveal-right, .reveal-scale, .stagger-children');
    const windowHeight = window.innerHeight;

    reveals.forEach(el => {
        const elementTop = el.getBoundingClientRect().top;
        const revealPoint = windowHeight - 100;

        if (elementTop < revealPoint) {
            el.classList.add('active');
        }
    });
}

// ---------- Smooth Scroll for Nav Links ----------
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'start'
            });
        }
    });
});

// ---------- Active Nav Link Highlight ----------
function updateActiveNavLink() {
    const sections = document.querySelectorAll('section[id]');
    const scrollY = window.scrollY + 120;

    sections.forEach(section => {
        const sectionTop = section.offsetTop;
        const sectionHeight = section.offsetHeight;
        const sectionId = section.getAttribute('id');

        if (scrollY >= sectionTop && scrollY < sectionTop + sectionHeight) {
            document.querySelectorAll('.nav-links a').forEach(link => {
                link.style.opacity = link.getAttribute('href') === `#${sectionId}` ? '1' : '';
                link.style.fontWeight = link.getAttribute('href') === `#${sectionId}` ? '700' : '';
            });
        }
    });
}

// ---------- Parallax on Hero Phone ----------
function parallaxPhone() {
    // Disable parallax on mobile to avoid overriding flex layout
    if (window.innerWidth <= 768) return;
    const scrollY = window.scrollY;
    const heroVisual = document.querySelector('.hero-visual');
    if (heroVisual && scrollY < window.innerHeight) {
        heroVisual.style.transform = `translateY(${scrollY * 0.08}px)`;
    }
}

// ---------- Counter Animation ----------
function animateCounters() {
    const counters = document.querySelectorAll('.stat-number');
    counters.forEach(counter => {
        if (counter.dataset.animated) return;
        const rect = counter.getBoundingClientRect();
        if (rect.top < window.innerHeight && rect.bottom > 0) {
            counter.dataset.animated = 'true';
            counter.style.transition = 'transform 0.5s var(--ease-out)';
            counter.style.transform = 'scale(1.1)';
            setTimeout(() => {
                counter.style.transform = 'scale(1)';
            }, 300);
        }
    });
}

// ---------- Interaction Support (Touch + Mouse) ----------
function setupTouchInteractions() {
    // iOS Safari fix: empty touchstart on document enables :active CSS
    document.addEventListener('touchstart', function () { }, { passive: true });

    // Helper: add interaction feedback via .touch-active class
    function addInteractionFeedback(selector, duration) {
        const elements = document.querySelectorAll(selector);
        if (!elements.length) return;

        elements.forEach(el => {
            function activate() {
                el.classList.add('touch-active');
            }
            function deactivate() {
                setTimeout(() => {
                    el.classList.remove('touch-active');
                }, duration);
            }
            function deactivateNow() {
                el.classList.remove('touch-active');
            }

            // Touch events (real mobile devices)
            el.addEventListener('touchstart', activate, { passive: true });
            el.addEventListener('touchend', deactivate, { passive: true });
            el.addEventListener('touchcancel', deactivateNow, { passive: true });

            // Mouse events (desktop + Chrome DevTools responsive emulation)
            el.addEventListener('mousedown', activate);
            el.addEventListener('mouseup', deactivate);
            el.addEventListener('mouseleave', deactivateNow);
        });
    }

    // Feature cards — lift + gradient bar on interaction
    addInteractionFeedback('.feature-card', 600);

    // About highlight items
    addInteractionFeedback('.about-highlight-item', 400);

    // Sponsor items
    addInteractionFeedback('.sponsor-item', 400);

    // Download button
    addInteractionFeedback('.download-btn', 400);

    // Back-to-top button
    addInteractionFeedback('.back-to-top', 300);

    // Hero CTA buttons
    addInteractionFeedback('.btn-primary', 400);
    addInteractionFeedback('.btn-secondary', 400);

    // Preview carousel items
    addInteractionFeedback('.preview-item', 400);
}

// ---------- Event Listeners ----------
window.addEventListener('scroll', () => {
    handleNavbarScroll();
    revealOnScroll();
    updateActiveNavLink();
    parallaxPhone();
    animateCounters();
}, { passive: true });

window.addEventListener('resize', () => {
    if (window.innerWidth > 768) {
        closeMobileNav();
    }
    // Clear parallax transform on mobile
    if (window.innerWidth <= 768) {
        const heroVisual = document.querySelector('.hero-visual');
        if (heroVisual) heroVisual.style.transform = '';
    }
});

// ---------- Init ----------
document.addEventListener('DOMContentLoaded', () => {
    // Restore saved language
    const savedLang = localStorage.getItem('orion-lang');
    if (savedLang) {
        setLanguage(savedLang);
    }

    // Initial scroll checks
    handleNavbarScroll();
    revealOnScroll();

    // Setup touch interactions for mobile
    setupTouchInteractions();

    // YPAB Marquee: clone cards for seamless infinite scroll
    document.querySelectorAll('.ypab-marquee-track').forEach(track => {
        const cards = Array.from(track.children);
        cards.forEach(card => {
            const clone = card.cloneNode(true);
            clone.setAttribute('aria-hidden', 'true');
            track.appendChild(clone);
        });
    });

    // Add smooth loading feel
    document.body.style.opacity = '0';
    document.body.style.transition = 'opacity 0.5s ease';
    requestAnimationFrame(() => {
        document.body.style.opacity = '1';
    });
});
