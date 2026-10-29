import 'dart:io';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../core/nav.dart';
import '../core/theme.dart';
import '../services/api.dart';
import '../services/auth.dart';
import '../services/library.dart';
import '../widgets/avatar.dart';
import '../widgets/cards.dart';
import '../widgets/glass.dart';

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});
  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  int _tab = 0;
  List<HistoryItem>? _history;
  List<ListItem>? _favorites;
  List<ListItem>? _list;
  List<AvatarDeco> _decos = const [];

  @override
  void initState() {
    super.initState();
    Auth.instance.addListener(_reload);
    SiteApi.instance.decorations().then((d) {
      if (mounted) setState(() => _decos = d);
    });
    _reload();
  }

  @override
  void dispose() {
    Auth.instance.removeListener(_reload);
    super.dispose();
  }

  Future<void> _reload() async {
    final lib = Library.instance;
    final h = await lib.loadHistory();
    final f = await lib.load(Collection.favorites);
    final l = await lib.load(Collection.list);
    if (!mounted) return;
    setState(() {
      _history = h;
      _favorites = f;
      _list = l;
    });
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Auth.instance,
      builder: (context, _) {
        final auth = Auth.instance;
        final top = MediaQuery.of(context).padding.top;
        final joined = auth.user?.createdAt == null ? null : _date(auth.user!.createdAt);
        final tabs = [('History', _history?.length), ('Favorites', _favorites?.length), ('Watchlist', _list?.length)];
        return RefreshIndicator(
          onRefresh: () async {
            await auth.loadProfile();
            await _reload();
          },
          child: ListView(padding: const EdgeInsets.only(bottom: 120), children: [
            // banner
            SizedBox(
              height: 140 + top,
              width: double.infinity,
              child: Container(
                color: C.w(0.08),
                child: auth.banner == null ? null : CachedNetworkImage(imageUrl: auth.banner!, fit: BoxFit.cover, errorWidget: (_, __, ___) => const SizedBox()),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Transform.translate(
                  offset: const Offset(0, -48),
                  child: DecoratedAvatar(
                    size: 96,
                    deco: findDeco(auth.decoration, _decos),
                    child: Avatar(url: auth.signedIn ? auth.avatar : null),
                  ),
                ),
                Transform.translate(
                  offset: const Offset(0, -36),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(auth.signedIn ? (auth.name ?? '') : 'Guest', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, letterSpacing: -0.5)),
                    const SizedBox(height: 2),
                    Text(auth.signedIn ? (auth.handle ?? '') : 'Log in to set an avatar and banner and keep your lists on every device.', style: TextStyle(fontSize: 15, color: C.w(0.7))),
                    const SizedBox(height: 16),
                    if (auth.signedIn)
                      Row(children: [
                        Expanded(child: GlassButton(label: 'Edit profile', icon: Icons.edit_rounded, kind: GlassKind.accent, height: 42, radius: 12, onTap: () => _edit(context))),
                      ])
                    else if (auth.ready)
                      GlassButton(label: 'Log in / Sign up', kind: GlassKind.white, expand: true, height: 44, radius: 12, onTap: () => openLogin(context)),
                    if (auth.signedIn) ...[
                      const SizedBox(height: 22),
                      const Text('Bio', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                      const SizedBox(height: 6),
                      Text((auth.profile?.bio ?? '').isEmpty ? 'No bio yet.' : auth.profile!.bio!, style: TextStyle(fontSize: 15, height: 1.4, color: (auth.profile?.bio ?? '').isEmpty ? C.w(0.4) : C.w(0.8))),
                      if (joined != null) ...[
                        const SizedBox(height: 18),
                        const Text('Member Since', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                        const SizedBox(height: 8),
                        Row(children: [Image.asset('assets/icon.png', width: 22, height: 22), const SizedBox(width: 10), Text(joined, style: TextStyle(fontSize: 15, color: C.w(0.8)))]),
                      ],
                    ],
                  ]),
                ),
              ]),
            ),
            // tabs
            Container(
              margin: const EdgeInsets.only(top: 0),
              decoration: BoxDecoration(border: Border(bottom: BorderSide(color: C.w(0.15)))),
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(children: [
                for (var i = 0; i < tabs.length; i++)
                  Expanded(
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => setState(() => _tab = i),
                      child: Container(
                        height: 46,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(border: Border(bottom: BorderSide(color: _tab == i ? Colors.white : Colors.transparent, width: 2))),
                        child: Text('${tabs[i].$1}${tabs[i].$2 != null ? ' ${tabs[i].$2}' : ''}', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: _tab == i ? Colors.white : C.muted)),
                      ),
                    ),
                  ),
              ]),
            ),
            Padding(padding: const EdgeInsets.fromLTRB(16, 20, 16, 0), child: _tabBody()),
          ]),
        );
      },
    );
  }

  String _date(String iso) {
    final d = DateTime.tryParse(iso);
    if (d == null) return '';
    const m = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
    return '${m[d.month - 1]} ${d.day}, ${d.year}';
  }

  Widget _tabBody() {
    if (_tab == 0) {
      final items = _history;
      if (items == null) return const PosterGridSkeleton(count: 6);
      if (items.isEmpty) return _empty('Nothing here yet. Start watching something.');
      return GridView.builder(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, mainAxisSpacing: 16, crossAxisSpacing: 12, childAspectRatio: 1.25),
        itemCount: items.length,
        itemBuilder: (context, i) {
          final h = items[i];
          return HistoryCard(
            h: h,
            onTap: () async {
              await openWatch(context, h.animeId, episode: h.episode, dub: h.dub);
              _reload();
            },
            onRemove: () async {
              setState(() => _history = items.where((x) => x.animeId != h.animeId).toList());
              await Library.instance.removeHistory(h.animeId);
            },
          );
        },
      );
    }
    final c = _tab == 1 ? Collection.favorites : Collection.list;
    final items = _tab == 1 ? _favorites : _list;
    if (items == null) return const PosterGridSkeleton(count: 6);
    if (items.isEmpty) return _empty(_tab == 1 ? 'No favorites yet.' : 'Your watchlist is empty.');
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: posterGrid(),
      itemCount: items.length,
      itemBuilder: (context, i) {
        final it = items[i];
        return PosterTile(
          title: it.title,
          image: it.image,
          onTap: () => openInfo(context, it.animeId),
          onRemove: () async {
            setState(() {
              final rest = items.where((x) => x.animeId != it.animeId).toList();
              if (_tab == 1) {
                _favorites = rest;
              } else {
                _list = rest;
              }
            });
            await Library.instance.toggle(c, animeId: it.animeId, title: it.title, image: it.image);
          },
        );
      },
    );
  }

  Widget _empty(String text) => Padding(padding: const EdgeInsets.symmetric(vertical: 48), child: Center(child: Text(text, style: TextStyle(fontSize: 14, color: C.w(0.6)))));

  void _edit(BuildContext context) {
    showGlassSheet<void>(context, builder: (_) => const _EditProfile());
  }
}

/* ------------------------------- edit profile sheet ------------------------------- */

class _EditProfile extends StatefulWidget {
  const _EditProfile();
  @override
  State<_EditProfile> createState() => _EditProfileState();
}

class _EditProfileState extends State<_EditProfile> {
  final _auth = Auth.instance;
  late final TextEditingController _name = TextEditingController(text: _auth.profile?.username ?? _auth.name ?? '');
  late final TextEditingController _bio = TextEditingController(text: _auth.profile?.bio ?? '');
  late String? _deco = _auth.decoration;
  File? _avatarPick;
  File? _bannerPick;
  bool _dropAvatar = false;
  bool _dropBanner = false;
  bool _busy = false;
  String? _error;
  List<AvatarDeco> _decos = const [];

  @override
  void initState() {
    super.initState();
    SiteApi.instance.decorations().then((d) {
      if (mounted) setState(() => _decos = d);
    });
  }

  @override
  void dispose() {
    _name.dispose();
    _bio.dispose();
    super.dispose();
  }

  Future<void> _pick(bool avatar) async {
    final x = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: avatar ? 1024 : 2048, imageQuality: 92);
    if (x == null) return;
    setState(() {
      if (avatar) {
        _avatarPick = File(x.path);
        _dropAvatar = false;
      } else {
        _bannerPick = File(x.path);
        _dropBanner = false;
      }
    });
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    if (name.isEmpty) {
      setState(() => _error = 'Enter a display name.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final patch = <String, dynamic>{'username': name, 'bio': _bio.text.trim(), 'decoration': _deco};
      final oldAvatar = _auth.profile?.avatarUrl;
      final oldBanner = _auth.profile?.bannerUrl;
      if (_avatarPick != null) {
        patch['avatar_url'] = await _auth.uploadImage(_avatarPick!, 'avatar');
      } else if (_dropAvatar) {
        patch['avatar_url'] = null;
      }
      if (_bannerPick != null) {
        patch['banner_url'] = await _auth.uploadImage(_bannerPick!, 'banner');
      } else if (_dropBanner) {
        patch['banner_url'] = null;
      }
      await _auth.saveProfile(patch);
      if (patch.containsKey('avatar_url')) await _auth.deleteImage(oldAvatar);
      if (patch.containsKey('banner_url')) await _auth.deleteImage(oldBanner);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
    if (mounted) setState(() => _busy = false);
  }

  InputDecoration _dec(String hint) => InputDecoration(
        hintText: hint,
        hintStyle: TextStyle(color: C.w(0.4)),
        filled: false,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: C.w(0.2))),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: C.w(0.6))),
        disabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: C.w(0.1))),
      );

  Widget _label(String t) => Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(t.toUpperCase(), style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, letterSpacing: 0.6, color: C.w(0.6))));

  @override
  Widget build(BuildContext context) {
    final avatarUrl = _dropAvatar ? null : _auth.avatar;
    final bannerUrl = _dropBanner ? null : _auth.banner;
    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.92),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 18, 16, 20),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Expanded(child: Text('Edit profile', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
            GestureDetector(onTap: _busy ? null : () => Navigator.of(context).pop(), child: Icon(Icons.close_rounded, color: C.w(0.8))),
          ]),
          const SizedBox(height: 14),
          // preview
          Stack(clipBehavior: Clip.none, children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: SizedBox(
                height: 110,
                width: double.infinity,
                child: _bannerPick != null
                    ? Image.file(_bannerPick!, fit: BoxFit.cover)
                    : Container(color: C.w(0.08), child: bannerUrl == null ? null : CachedNetworkImage(imageUrl: bannerUrl, fit: BoxFit.cover, errorWidget: (_, __, ___) => const SizedBox())),
              ),
            ),
            Positioned(
              left: 12,
              bottom: -34,
              child: DecoratedAvatar(
                size: 76,
                deco: findDeco(_deco, _decos),
                child: _avatarPick != null ? Image.file(_avatarPick!, fit: BoxFit.cover) : Avatar(url: avatarUrl),
              ),
            ),
          ]),
          const SizedBox(height: 46),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                _label('Avatar'),
                Row(children: [
                  GlassButton(label: 'Change', icon: Icons.photo_camera_rounded, height: 38, fontSize: 13, padding: const EdgeInsets.symmetric(horizontal: 14), onTap: _busy ? null : () => _pick(true)),
                  if (_avatarPick != null || _auth.avatar != null) ...[
                    const SizedBox(width: 8),
                    GlassIconButton(icon: Icons.delete_outline_rounded, size: 38, iconSize: 19, onTap: _busy ? null : () => setState(() {
                          _avatarPick = null;
                          _dropAvatar = true;
                        })),
                  ],
                ]),
              ]),
            ),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                _label('Banner · GIFs work'),
                Row(children: [
                  GlassButton(label: 'Change', icon: Icons.add_photo_alternate_rounded, height: 38, fontSize: 13, padding: const EdgeInsets.symmetric(horizontal: 14), onTap: _busy ? null : () => _pick(false)),
                  if (_bannerPick != null || _auth.banner != null) ...[
                    const SizedBox(width: 8),
                    GlassIconButton(icon: Icons.delete_outline_rounded, size: 38, iconSize: 19, onTap: _busy ? null : () => setState(() {
                          _bannerPick = null;
                          _dropBanner = true;
                        })),
                  ],
                ]),
              ]),
            ),
          ]),
          const SizedBox(height: 18),
          _label('Avatar decoration'),
          DecorationPicker(value: _deco, onChanged: (v) => setState(() => _deco = v)),
          const SizedBox(height: 18),
          _label('Display name'),
          TextField(controller: _name, maxLength: 32, enabled: !_busy, style: const TextStyle(fontSize: 16), decoration: _dec('Your name').copyWith(counterText: '')),
          const SizedBox(height: 14),
          _label('Bio'),
          TextField(controller: _bio, maxLength: 190, maxLines: 3, enabled: !_busy, style: const TextStyle(fontSize: 16), decoration: _dec('Tell people what you watch')),
          if (_error != null)
            Container(
              margin: const EdgeInsets.only(top: 6),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(color: const Color(0x1AEF4444), borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0x4DF87171))),
              child: Text(_error!, style: const TextStyle(fontSize: 12.5, color: Color(0xFFFCA5A5))),
            ),
          const SizedBox(height: 16),
          Row(mainAxisAlignment: MainAxisAlignment.end, children: [
            GlassButton(label: 'Cancel', height: 44, onTap: _busy ? null : () => Navigator.of(context).pop()),
            const SizedBox(width: 10),
            GlassButton(label: 'Save', kind: GlassKind.white, height: 44, padding: const EdgeInsets.symmetric(horizontal: 28), busy: _busy, onTap: _save),
          ]),
        ]),
      ),
    );
  }
}
